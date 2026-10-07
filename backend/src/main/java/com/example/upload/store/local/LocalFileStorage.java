package com.example.upload.store.local;

import com.example.upload.config.UploadProperties;
import com.example.upload.store.spi.ContentHandle;
import com.example.upload.store.spi.FileStorage;
import com.example.upload.store.spi.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 本地磁盘最终文件存储。合并 = FileChannel 按分片号顺序追加；
 * 自带两类恢复路径（对抗性中断测试沉淀）：
 * ① 目标已存在且大小一致 → 上次合并成功但索引写入失败，直接复用；
 * ② 目标已存在但大小不一致 → 上次合并中途死亡留下的残缺文件，删除重做。
 * 存储布局 {baseDir}/files/{yyyyMM}/{hash}_{fileName}，locator 即相对 baseDir 的路径。
 */
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final Path baseDir;
    private final Path filesDir;
    private final Path chunksDir;

    public LocalFileStorage(UploadProperties props) throws IOException {
        this.baseDir = Path.of(props.getBaseDir()).toAbsolutePath().normalize();
        this.filesDir = baseDir.resolve("files");
        this.chunksDir = baseDir.resolve("chunks");
        Files.createDirectories(filesDir);
    }

    @Override
    public StoredObject completeMerge(String fileHash, String safeFileName, long totalSize, int totalChunks)
            throws IOException {
        Path targetDir = filesDir.resolve(LocalDateTime.now().format(MONTH_FMT));
        Path target = targetDir.resolve(fileHash + "_" + safeFileName);
        Files.createDirectories(targetDir);

        if (Files.exists(target) && Files.size(target) == totalSize) {
            log.warn("目标文件已存在且大小一致，跳过合并直接入索引: {}", target);
        } else {
            if (Files.exists(target)) {
                log.warn("目标文件已存在但大小不一致（{} 字节），删除后重新合并: {}", Files.size(target), target);
                Files.deleteIfExists(target);
            }
            mergeChunks(fileHash, totalChunks, target);
        }
        return new StoredObject(baseDir.relativize(target).toString().replace('\\', '/'), Files.size(target));
    }

    /** FileChannel 按分片号顺序追加，兼容分片乱序到达（状态在磁盘上，与到达顺序无关） */
    private void mergeChunks(String fileHash, int totalChunks, Path target) throws IOException {
        try (FileChannel out = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunksDir.resolve(fileHash).resolve(i + ".part");
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

    @Override
    public StoredObject stat(String locator) throws IOException {
        Path path = resolve(locator);
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(locator);
        }
        return new StoredObject(locator, Files.size(path));
    }

    @Override
    public ContentHandle open(String locator) throws IOException {
        Path path = resolve(locator);
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(locator);
        }
        return new LocalContentHandle(path);
    }

    @Override
    public void delete(String locator) {
        try {
            Path path = resolve(locator);
            Files.deleteIfExists(path);
            // 月份目录为多文件共享，仅删除文件引用；目录空了才连带清理
            // （此前误删整个目录，会连带删除同月合并的其他文件——JMeter 下载用例发现）
            Files.deleteIfExists(path.getParent());
        } catch (IOException e) {
            log.warn("删除文件或空目录失败: {}", locator, e);
        }
    }

    @Override
    public long usableBytes() {
        return filesDir.toFile().getUsableSpace();
    }

    /** locator 只来自本后端写入的元数据；仍做规范化 + 越界防护，杜绝构造 locator 逃出 baseDir */
    private Path resolve(String locator) {
        Path path = baseDir.resolve(locator).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法存储路径: " + locator);
        }
        return path;
    }
}
