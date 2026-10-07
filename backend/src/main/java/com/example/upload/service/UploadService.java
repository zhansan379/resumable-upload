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
import com.example.upload.store.spi.ChunkInfo;
import com.example.upload.store.spi.ChunkStorage;
import com.example.upload.store.spi.ContentHandle;
import com.example.upload.store.spi.FileStorage;
import com.example.upload.store.spi.MergeLock;
import com.example.upload.store.spi.MetadataStore;
import com.example.upload.store.spi.StoredObject;
import com.example.upload.store.spi.UploadEventListener;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 上传核心服务（协议层/编排层）。存储动作全部经由 SPI 委托：
 * {@link ChunkStorage} 分片读写、{@link FileStorage} 合并产物、{@link MetadataStore} 秒传索引、
 * {@link MergeLock} 合并互斥，因此本地磁盘 / 对象存储（S3 兼容）、单实例 / 多实例切换不影响本类与 HTTP 协议。
 * 租户：启用租户头后，秒传/删除/下载/列表按租户分域；存储层收到的是 {tenant}/{hash} 作用域 ID。
 * 生命周期事件经 {@link UploadEventListener} 广播给集成方，单个监听器异常不影响主流程。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** 前端 spark-md5 产出 32 位小写 MD5；放宽到 8-64 位 hex 以兼容 SHA-1/SHA-256 */
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{8,64}$");
    /** 租户标识进入存储路径与数据库键，必须限定字符集杜绝路径注入 */
    private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UploadProperties props;
    private final MetadataStore indexStore;
    private final ChunkStorage chunkStorage;
    private final FileStorage fileStorage;
    private final MergeLock mergeLock;
    private final TenantResolver tenantResolver;
    private final List<UploadEventListener> listeners;
    private final long maxTotalBytes;

    /** MD5 后台校验线程池（单线程足够，校验慢于合并是常态） */
    private final ExecutorService verifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "md5-verify");
        t.setDaemon(true);
        return t;
    });

    public UploadService(UploadProperties props, MetadataStore indexStore,
                         ChunkStorage chunkStorage, FileStorage fileStorage,
                         MergeLock mergeLock, TenantResolver tenantResolver,
                         List<UploadEventListener> listeners) {
        this.props = props;
        this.indexStore = indexStore;
        this.chunkStorage = chunkStorage;
        this.fileStorage = fileStorage;
        this.mergeLock = mergeLock;
        this.tenantResolver = tenantResolver;
        this.listeners = listeners;
        this.maxTotalBytes = DataSize.parse(props.getMaxTotalSize()).toBytes();
    }

    /* ---------------- 租户 ---------------- */

    /**
     * 解析当前请求的租户：来源由 {@link TenantResolver} 决定（默认读请求头，
     * 宿主可用自己的 Bean 覆盖以对接 JWT/SecurityContext 等已有租户体系）；
     * 字符集校验集中在本层（租户会进入存储路径与数据库键，路径注入防护不可被实现绕过）。
     */
    public String currentTenant(HttpServletRequest request) {
        String tenant = tenantResolver.resolve(request);
        if (tenant == null) {
            return null;
        }
        if (!TENANT_PATTERN.matcher(tenant).matches()) {
            throw BusinessException.badRequest("租户标识非法（应为 1-64 位字母数字._-）");
        }
        return tenant;
    }

    /** 存储层作用域 ID：启用租户时 {tenant}/{hash}，存储实现按不透明字符串处理（路径/键天然分域） */
    private String scopedId(String tenant, String hash) {
        return tenant == null ? hash : tenant + "/" + hash;
    }

    /* ---------------- check：秒传 + 断点续传探测 ---------------- */

    public CheckResponse check(CheckRequest req, String tenant) {
        String hash = validateHash(req.fileHash());
        FileRecord rec = indexStore.get(tenant, hash);
        if (rec != null && fileStorage.exists(rec.getStoredPath())) {
            return new CheckResponse(true, List.of(), downloadUrl(hash),
                    rec.getFileName(), rec.getSize(), rec.isVerified());
        }
        return new CheckResponse(false, listUploadedChunks(tenant, hash), null, null, null, null);
    }

    /** 已传分片编号列表（0 起），来源是存储端的实际分片状态 */
    private List<Integer> listUploadedChunks(String tenant, String hash) {
        try {
            return chunkStorage.list(scopedId(tenant, hash)).stream().map(ChunkInfo::index).toList();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分片目录失败：" + e.getMessage());
        }
    }

    /** 下载 URL 统一在此构造：前缀跟随 app.upload.api-prefix 配置 */
    private String downloadUrl(String hash) {
        return props.getApiPrefix() + "/files/" + hash + "/download";
    }

    /* ---------------- 分片保存 ---------------- */

    public ChunkSavedResponse saveChunk(String fileHash, Integer chunkIndex, Integer totalChunks,
                                        MultipartFile chunk, String tenant) {
        String hash = validateHash(fileHash);
        // 参数校验优先于状态校验（JMeter 用例验证过：已存在文件 + 非法参数应报 400 而非 409）
        if (chunkIndex == null || chunkIndex < 0) {
            throw BusinessException.badRequest("chunkIndex 非法");
        }
        if (totalChunks == null || totalChunks < 1 || totalChunks > effectiveMaxChunks()) {
            throw BusinessException.badRequest("totalChunks 非法（1 ~ " + effectiveMaxChunks() + "）");
        }
        if (chunkIndex >= totalChunks) {
            throw BusinessException.badRequest("chunkIndex 超出 totalChunks 范围");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw BusinessException.badRequest("分片内容为空");
        }
        // S3 等后端的硬约束前置到保存阶段，避免到合并才失败（能力声明见 ChunkStorage）
        long minNonLast = chunkStorage.minNonLastPartSize();
        if (chunkIndex < totalChunks - 1 && chunk.getSize() < minNonLast) {
            throw BusinessException.badRequest(
                    "存储后端要求非末片分片至少 " + minNonLast + " 字节（当前 " + chunk.getSize() + "），请增大分片大小");
        }
        if (indexStore.containsKey(tenant, hash)) {
            throw BusinessException.conflict("该文件已存在（秒传命中），无需再上传分片");
        }

        long stored;
        try {
            stored = chunkStorage.save(scopedId(tenant, hash), chunkIndex, totalChunks,
                    chunk.getSize(), chunk.getInputStream());
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "分片保存失败：" + e.getMessage());
        }
        return new ChunkSavedResponse(chunkIndex, stored);
    }

    /** 协议层分片上限 = 配置上限与存储后端能力上限的较小值（S3 multipart 为 10000） */
    private int effectiveMaxChunks() {
        return Math.min(props.getMaxTotalChunks(), chunkStorage.maxChunks());
    }

    /* ---------------- 合并 ---------------- */

    public MergeResponse merge(MergeRequest req, String tenant) {
        String hash = validateHash(req.fileHash());
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw BusinessException.badRequest("fileName 不能为空");
        }
        if (req.totalChunks() < 1 || req.totalChunks() > effectiveMaxChunks()) {
            throw BusinessException.badRequest("totalChunks 非法（1 ~ " + effectiveMaxChunks() + "）");
        }
        if (req.totalSize() <= 0) {
            throw BusinessException.badRequest("totalSize 非法");
        }
        if (req.totalSize() > maxTotalBytes) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件总大小超过服务端上限 " + props.getMaxTotalSize());
        }

        // 容量水位前置检查（合并需要再写一份完整文件，分片仍在存储上）。
        // 本地磁盘不能指望 ENOSPC——ext4 延迟分配可能让它静默通过并留下残缺文件，必须显式拒绝（tus 语义 507）；
        // 对象存储等无法给出容量的后端返回 <=0，跳过检查
        long usable = fileStorage.usableBytes();
        if (usable > 0 && usable < req.totalSize()) {
            throw new BusinessException(HttpStatus.INSUFFICIENT_STORAGE,
                    "服务器可用空间不足（剩余 " + usable / 1048576 + "MB，需要 " + req.totalSize() / 1048576 + "MB），请稍后重试或清理空间");
        }

        String safeName = sanitizeFileName(req.fileName());
        String scoped = scopedId(tenant, hash);
        // 合并互斥：本地锁（单实例）或数据库租约锁（多实例，见 MergeLock 实现）
        return mergeLock.withLock(scoped, () -> doMerge(tenant, hash, scoped, safeName, req));
    }

    private MergeResponse doMerge(String tenant, String hash, String scoped, String safeName, MergeRequest req) {
        // 重复合并保护：已入库且产物存在，直接按秒传返回
        FileRecord exist = indexStore.get(tenant, hash);
        if (exist != null && fileStorage.exists(exist.getStoredPath())) {
            return new MergeResponse(hash, exist.getFileName(), downloadUrl(hash),
                    exist.getSize(), exist.isVerified());
        }

        // 1. 分片齐全性 + 总字节校验（一次 list 同时拿到编号与每片大小）
        List<ChunkInfo> infos;
        try {
            infos = chunkStorage.list(scoped);
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分片目录失败：" + e.getMessage());
        }
        Set<Integer> have = new HashSet<>();
        long sum = 0;
        for (ChunkInfo info : infos) {
            have.add(info.index());
            sum += info.size();
        }
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
        if (sum != req.totalSize()) {
            throw BusinessException.conflict("分片总大小 " + sum + " 字节与文件大小 " + req.totalSize() + " 字节不一致，请重新上传");
        }

        // 2. 固化为最终文件（合并/恢复语义由存储后端负责）
        StoredObject stored;
        try {
            stored = fileStorage.completeMerge(scoped, safeName, req.totalSize(), req.totalChunks());
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "合并失败：" + e.getMessage());
        }
        if (stored.size() != req.totalSize()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "合并后大小 " + stored.size() + " 与预期 " + req.totalSize() + " 不一致");
        }

        // 3. 清理分片 + 写索引 + 后台校验 + 事件
        chunkStorage.deleteAll(scoped);
        FileRecord rec = new FileRecord(tenant, hash, req.fileName(), req.totalSize(),
                stored.locator(), System.currentTimeMillis(), false);
        indexStore.put(rec);
        if (props.isVerifyMd5AfterMerge()) {
            verifyMd5Async(rec);
        }
        notifyListeners(l -> l.onMergeCompleted(rec));
        log.info("合并完成: {} ({} bytes, {} chunks)", stored.locator(), req.totalSize(), req.totalChunks());
        return new MergeResponse(hash, req.fileName(), downloadUrl(hash), req.totalSize(), false);
    }

    /* ---------------- 完整性校验（后台） ---------------- */

    private void verifyMd5Async(FileRecord rec) {
        verifyExecutor.submit(() -> {
            try (ContentHandle handle = fileStorage.open(rec.getStoredPath())) {
                String actual = md5(handle.readFully());
                boolean ok = actual.equalsIgnoreCase(rec.getFileHash());
                indexStore.updateVerified(rec.getTenant(), rec.getFileHash(), ok);
                notifyListeners(l -> l.onVerifyCompleted(rec, ok));
                if (ok) {
                    log.info("MD5 校验通过: {}", rec.getFileName());
                } else {
                    log.error("MD5 校验失败! 期望 {} 实际 {} 文件: {}", rec.getFileHash(), actual, rec.getStoredPath());
                }
            } catch (Exception e) {
                log.error("MD5 校验执行失败: {}", rec.getStoredPath(), e);
            }
        });
    }

    private String md5(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (in) {
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

    public List<FileItem> listFiles(String tenant) {
        return indexStore.all().stream()
                .filter(r -> tenant == null || tenant.equals(r.getTenant()))
                .map(r -> new FileItem(r.getFileHash(), r.getFileName(), r.getSize(),
                        TIME_FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(r.getUploadTime()), ZoneId.systemDefault())),
                        r.isVerified(), downloadUrl(r.getFileHash())))
                .sorted(Comparator.comparingLong((FileItem f) -> 0L)) // 顺序由前端处理
                .toList();
    }

    public record DownloadInfo(String fileName, long size, String locator) {
    }

    public DownloadInfo downloadInfo(String fileHash, String tenant) {
        String hash = validateHash(fileHash);
        FileRecord rec = indexStore.get(tenant, hash);
        if (rec == null) {
            throw BusinessException.notFound("文件不存在: " + hash);
        }
        try {
            StoredObject so = fileStorage.stat(rec.getStoredPath());
            return new DownloadInfo(rec.getFileName(), so.size(), so.locator());
        } catch (FileNotFoundException | NoSuchFileException e) {
            throw BusinessException.notFound("文件已丢失: " + hash);
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件信息失败");
        }
    }

    /** 打开最终文件内容句柄（控制器用它做全量下载与 Range 区间下载） */
    public ContentHandle openContent(String locator) {
        try {
            return fileStorage.open(locator);
        } catch (FileNotFoundException | NoSuchFileException e) {
            throw BusinessException.notFound("文件已丢失");
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件信息失败");
        }
    }

    public boolean deleteFile(String fileHash, String tenant) {
        String hash = validateHash(fileHash);
        FileRecord rec = indexStore.get(tenant, hash);
        boolean removed = indexStore.remove(tenant, hash);
        if (rec != null) {
            // 存储后端只删目标文件本身及实现私有的附属物（本地实现仅清理空目录）
            fileStorage.delete(rec.getStoredPath());
            notifyListeners(l -> l.onDeleted(hash));
        }
        chunkStorage.deleteAll(scopedId(tenant, hash));
        return removed;
    }

    /** 取消上传 / 清理孤儿分片（filepond 的 revert 端点思想） */
    public boolean cleanChunks(String fileHash, String tenant) {
        String hash = validateHash(fileHash);
        chunkStorage.deleteAll(scopedId(tenant, hash));
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

    private void notifyListeners(Consumer<UploadEventListener> call) {
        for (UploadEventListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (Exception e) {
                log.warn("上传事件监听器执行失败: {}", listener.getClass().getName(), e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        verifyExecutor.shutdownNow();
    }

    /**
     * 启动时补做未完成的 MD5 校验：异步校验任务随进程死亡丢失，
     * 不补做的话 verified 会永久停留在 false（对抗性中断测试发现的场景）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reverifyOnStartup() {
        if (!props.isVerifyMd5AfterMerge()) {
            return;
        }
        List<FileRecord> pending = indexStore.all().stream()
                .filter(r -> !r.isVerified())
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        log.info("启动校验：发现 {} 个未完成 MD5 校验的文件，重新入队", pending.size());
        pending.forEach(rec -> {
            try {
                if (fileStorage.exists(rec.getStoredPath())) {
                    verifyMd5Async(rec);
                } else {
                    log.warn("启动校验跳过（文件缺失）: {}", rec.getStoredPath());
                }
            } catch (Exception e) {
                log.warn("启动校验跳过（读取失败）: {}", rec.getStoredPath(), e);
            }
        });
    }

    /**
     * 定时清理孤儿分片：客户端消失（崩溃/断网/直接关页面）时取消接口不会被调用，
     * 分片会永久残留，按"最后更新时间 + TTL"兜底清理（本地按目录 mtime，S3 按上传会话时间）。
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT60S")
    public void cleanStaleChunks() {
        Instant deadline = Instant.now().minus(props.getChunkTtl());
        int cleaned = chunkStorage.deleteOrphansOlderThan(deadline);
        if (cleaned > 0) {
            log.info("过期分片清理完成: {} 个目录", cleaned);
        }
    }
}
