package com.example.upload.service;

import com.example.upload.config.UploadProperties;
import com.example.upload.dto.CheckRequest;
import com.example.upload.dto.CheckResponse;
import com.example.upload.dto.ChunkSavedResponse;
import com.example.upload.dto.FileItem;
import com.example.upload.dto.MergeRequest;
import com.example.upload.dto.MergeResponse;
import com.example.upload.exception.BusinessException;
import com.example.upload.store.FileRecord;
import com.example.upload.store.HashIndexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 上传核心服务。设计要点（对应 docs/01-开源项目调研分析.md 第四节）：
 * - 磁盘为事实源：已传分片 = 分片目录下的 {index}.part 文件，服务重启不丢断点状态；
 * - 分片写入：临时文件 + 原子移动，杜绝"半个分片"被当作已传；
 * - 秒传：hash 索引命中且最终文件存在，check 直接返回 finished；
 * - 合并：fileHash 粒度加锁防并发重复合并，按分片号顺序 FileChannel 追加，先校验分片齐全与总字节一致；
 * - 完整性：合并完成后后台单线程重算整体 MD5 与 hash 比对（不阻塞合并响应）。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** 前端 spark-md5 产出 32 位小写 MD5；放宽到 8-64 位 hex 以兼容 SHA-1/SHA-256 */
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{8,64}$");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UploadProperties props;
    private final HashIndexStore indexStore;
    private final Path baseDir;
    private final Path chunksDir;
    private final Path filesDir;
    private final long maxTotalBytes;

    /** 每个 fileHash 一把合并锁，防止同一文件被并发合并 */
    private final ConcurrentHashMap<String, Object> mergeLocks = new ConcurrentHashMap<>();

    /** MD5 后台校验线程池（单线程足够，校验慢于合并是常态） */
    private final ExecutorService verifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "md5-verify");
        t.setDaemon(true);
        return t;
    });

    public UploadService(UploadProperties props, HashIndexStore indexStore) throws IOException {
        this.props = props;
        this.indexStore = indexStore;
        this.baseDir = Paths.get(props.getBaseDir()).toAbsolutePath().normalize();
        this.chunksDir = baseDir.resolve("chunks");
        this.filesDir = baseDir.resolve("files");
        this.maxTotalBytes = DataSize.parse(props.getMaxTotalSize()).toBytes();
        Files.createDirectories(chunksDir);
        Files.createDirectories(filesDir);
    }

    /* ---------------- check：秒传 + 断点续传探测 ---------------- */

    public CheckResponse check(CheckRequest req) {
        String hash = validateHash(req.fileHash());
        FileRecord rec = indexStore.get(hash);
        if (rec != null && Files.isRegularFile(baseDir.resolve(rec.getStoredPath()))) {
            return new CheckResponse(true, List.of(), "/api/files/" + hash + "/download",
                    rec.getFileName(), rec.getSize(), rec.isVerified());
        }
        return new CheckResponse(false, listUploadedChunks(hash), null, null, null, null);
    }

    /** 已传分片编号列表（0 起），来源是分片目录下的实际文件 */
    private List<Integer> listUploadedChunks(String hash) {
        Path dir = chunksDir.resolve(hash);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".part"))
                    .map(n -> n.substring(0, n.length() - ".part".length()))
                    .forEach(n -> {
                        try {
                            result.add(Integer.parseInt(n));
                        } catch (NumberFormatException ignored) {
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分片目录失败：" + e.getMessage());
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /* ---------------- 分片保存 ---------------- */

    public ChunkSavedResponse saveChunk(String fileHash, Integer chunkIndex, Integer totalChunks, MultipartFile chunk) {
        String hash = validateHash(fileHash);
        // 参数校验优先于状态校验（JMeter 用例验证过：已存在文件 + 非法参数应报 400 而非 409）
        if (chunkIndex == null || chunkIndex < 0) {
            throw BusinessException.badRequest("chunkIndex 非法");
        }
        if (totalChunks == null || totalChunks < 1 || totalChunks > props.getMaxTotalChunks()) {
            throw BusinessException.badRequest("totalChunks 非法（1 ~ " + props.getMaxTotalChunks() + "）");
        }
        if (chunkIndex >= totalChunks) {
            throw BusinessException.badRequest("chunkIndex 超出 totalChunks 范围");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw BusinessException.badRequest("分片内容为空");
        }
        if (indexStore.containsKey(hash)) {
            throw BusinessException.conflict("该文件已存在（秒传命中），无需再上传分片");
        }

        Path dir = chunksDir.resolve(hash);
        Path tmp = dir.resolve(chunkIndex + ".part.tmp");
        Path target = dir.resolve(chunkIndex + ".part");
        try {
            Files.createDirectories(dir);
            try (InputStream in = chunk.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            atomicMove(tmp, target);
            return new ChunkSavedResponse(chunkIndex, Files.size(target));
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "分片保存失败：" + e.getMessage());
        }
    }

    private void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /* ---------------- 合并 ---------------- */

    public MergeResponse merge(MergeRequest req) {
        String hash = validateHash(req.fileHash());
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw BusinessException.badRequest("fileName 不能为空");
        }
        if (req.totalChunks() < 1 || req.totalChunks() > props.getMaxTotalChunks()) {
            throw BusinessException.badRequest("totalChunks 非法（1 ~ " + props.getMaxTotalChunks() + "）");
        }
        if (req.totalSize() <= 0) {
            throw BusinessException.badRequest("totalSize 非法");
        }
        if (req.totalSize() > maxTotalBytes) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件总大小超过服务端上限 " + props.getMaxTotalSize());
        }

        String safeName = sanitizeFileName(req.fileName());
        Object lock = mergeLocks.computeIfAbsent(hash, k -> new Object());
        synchronized (lock) {
            // 重复合并保护：已入库且文件在盘上，直接按秒传返回
            FileRecord exist = indexStore.get(hash);
            if (exist != null && Files.isRegularFile(baseDir.resolve(exist.getStoredPath()))) {
                return new MergeResponse(hash, exist.getFileName(), "/api/files/" + hash + "/download",
                        exist.getSize(), exist.isVerified());
            }

            // 1. 分片齐全性校验
            Set<Integer> have = new HashSet<>(listUploadedChunks(hash));
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < req.totalChunks(); i++) {
                if (!have.contains(i)) {
                    missing.add(i);
                }
            }
            if (!missing.isEmpty()) {
                String shown = missing.subList(0, Math.min(10, missing.size())).toString();
                throw BusinessException.conflict("分片缺失 " + missing.size() + " 个：" + shown
                        + (missing.size() > 10 ? " ..." : "") + "，请继续上传后重试");
            }

            // 2. 总字节校验
            long sum = 0;
            for (int i = 0; i < req.totalChunks(); i++) {
                try {
                    sum += Files.size(chunksDir.resolve(hash).resolve(i + ".part"));
                } catch (IOException e) {
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分片大小失败：" + e.getMessage());
                }
            }
            if (sum != req.totalSize()) {
                throw BusinessException.conflict("分片总大小 " + sum + " 字节与文件大小 " + req.totalSize() + " 字节不一致，请重新上传");
            }

            // 3. 按分片号顺序合并
            String month = LocalDateTime.now().format(MONTH_FMT);
            Path targetDir = filesDir.resolve(month);
            Path target = targetDir.resolve(hash + "_" + safeName);
            try {
                Files.createDirectories(targetDir);
                if (Files.exists(target)) {
                    // 上次合并成功但索引写入失败的恢复路径：文件已完整，直接复用
                    log.warn("目标文件已存在，跳过合并直接入索引: {}", target);
                } else {
                    mergeChunks(hash, req.totalChunks(), target);
                    long merged = Files.size(target);
                    if (merged != req.totalSize()) {
                        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "合并后大小 " + merged + " 与预期 " + req.totalSize() + " 不一致");
                    }
                }
            } catch (IOException e) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "合并失败：" + e.getMessage());
            }

            // 4. 清理分片目录 + 写索引 + 后台校验
            deleteDirQuietly(chunksDir.resolve(hash));
            FileRecord rec = new FileRecord(hash, req.fileName(), req.totalSize(),
                    baseDir.relativize(target).toString().replace('\\', '/'),
                    System.currentTimeMillis(), false);
            indexStore.put(rec);
            if (props.isVerifyMd5AfterMerge()) {
                verifyMd5Async(rec);
            }
            log.info("合并完成: {} ({} bytes, {} chunks)", target, req.totalSize(), req.totalChunks());
            return new MergeResponse(hash, req.fileName(), "/api/files/" + hash + "/download", req.totalSize(), false);
        }
    }

    /** FileChannel 按分片号顺序追加，兼容分片乱序到达（状态在磁盘上，与到达顺序无关） */
    private void mergeChunks(String hash, int totalChunks, Path target) throws IOException {
        try (FileChannel out = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunksDir.resolve(hash).resolve(i + ".part");
                try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                    long pos = 0;
                    long size = in.size();
                    while (pos < size) {
                        long n = in.transferTo(pos, size - pos, out);
                        if (n <= 0) {
                            throw new IOException("分片 " + i + " 拷贝异常，已传输 " + pos + "/" + size);
                        }
                        pos += n;
                    }
                }
            }
        }
    }

    /* ---------------- 完整性校验（后台） ---------------- */

    private void verifyMd5Async(FileRecord rec) {
        verifyExecutor.submit(() -> {
            Path path = baseDir.resolve(rec.getStoredPath());
            try {
                String actual = md5(path);
                boolean ok = actual.equalsIgnoreCase(rec.getFileHash());
                indexStore.updateVerified(rec.getFileHash(), ok);
                if (ok) {
                    log.info("MD5 校验通过: {}", rec.getFileName());
                } else {
                    log.error("MD5 校验失败! 期望 {} 实际 {} 文件: {}", rec.getFileHash(), actual, path);
                }
            } catch (Exception e) {
                log.error("MD5 校验执行失败: {}", path, e);
            }
        });
    }

    private String md5(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /* ---------------- 文件列表 / 下载 / 删除 ---------------- */

    public List<FileItem> listFiles() {
        return indexStore.all().stream()
                .map(r -> new FileItem(r.getFileHash(), r.getFileName(), r.getSize(),
                        TIME_FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getUploadTime()), ZoneId.systemDefault())),
                        r.isVerified(), "/api/files/" + r.getFileHash() + "/download"))
                .sorted(Comparator.comparingLong((FileItem f) -> 0L)) // 顺序由前端处理
                .toList();
    }

    public record DownloadInfo(Path path, String fileName, long size) {
    }

    public DownloadInfo downloadInfo(String fileHash) {
        String hash = validateHash(fileHash);
        FileRecord rec = indexStore.get(hash);
        if (rec == null) {
            throw BusinessException.notFound("文件不存在: " + hash);
        }
        Path path = baseDir.resolve(rec.getStoredPath());
        if (!Files.isRegularFile(path)) {
            throw BusinessException.notFound("文件已丢失: " + hash);
        }
        try {
            return new DownloadInfo(path, rec.getFileName(), Files.size(path));
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件信息失败");
        }
    }

    public boolean deleteFile(String fileHash) {
        String hash = validateHash(fileHash);
        FileRecord rec = indexStore.get(hash);
        boolean removed = indexStore.remove(hash);
        if (rec != null) {
            // 只删目标文件本身；月份目录为多文件共享，仅在目录为空时连带清理
            // （此前误删整个目录，会连带删除同月合并的其他文件——JMeter 下载用例发现）
            try {
                Files.deleteIfExists(baseDir.resolve(rec.getStoredPath()));
                Files.deleteIfExists(baseDir.resolve(rec.getStoredPath()).getParent());
            } catch (IOException e) {
                log.warn("删除文件或空目录失败: {}", rec.getStoredPath(), e);
            }
        }
        deleteDirQuietly(chunksDir.resolve(hash));
        return removed;
    }

    /** 取消上传 / 清理孤儿分片（filepond 的 revert 端点思想） */
    public boolean cleanChunks(String fileHash) {
        String hash = validateHash(fileHash);
        deleteDirQuietly(chunksDir.resolve(hash));
        return true;
    }

    /* ---------------- 工具 ---------------- */

    private String validateHash(String hash) {
        if (hash == null || !HASH_PATTERN.matcher(hash).matches()) {
            throw BusinessException.badRequest("fileHash 非法（应为 8-64 位十六进制）");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private String sanitizeFileName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (n.startsWith(".")) {
            n = "_" + n;
        }
        if (n.length() > 120) {
            // 只截掉前缀，保留扩展名
            n = n.substring(n.length() - 120);
        }
        return n.isEmpty() ? "file" : n;
    }

    private void deleteDirQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("遍历目录失败: {}", dir, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        verifyExecutor.shutdownNow();
    }
}
