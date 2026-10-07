package com.example.upload.store.local;

import com.example.upload.config.UploadProperties;
import com.example.upload.store.spi.ChunkInfo;
import com.example.upload.store.spi.ChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本地磁盘分片存储。磁盘为事实源：已传分片 = 分片目录下的 {index}.part 文件，
 * 服务重启不丢断点状态；写入走"临时文件 + 原子移动"，重复写同一分片幂等（后到者为准）。
 */
public class LocalChunkStorage implements ChunkStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalChunkStorage.class);

    private final Path chunksDir;

    public LocalChunkStorage(UploadProperties props) throws IOException {
        this.chunksDir = Path.of(props.getBaseDir()).toAbsolutePath().normalize().resolve("chunks");
        Files.createDirectories(chunksDir);
    }

    @Override
    public long save(String fileHash, int chunkIndex, int totalChunks, long contentLength, InputStream in)
            throws IOException {
        Path dir = chunksDir.resolve(fileHash);
        Path tmp = dir.resolve(chunkIndex + ".part.tmp");
        Path target = dir.resolve(chunkIndex + ".part");
        Files.createDirectories(dir);
        try (in) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        LocalIo.atomicMove(tmp, target);
        long stored = Files.size(target);
        if (contentLength >= 0 && stored != contentLength) {
            throw new IOException("分片落盘字节数 " + stored + " 与声明 " + contentLength + " 不一致");
        }
        return stored;
    }

    @Override
    public List<ChunkInfo> list(String fileHash) throws IOException {
        Path dir = chunksDir.resolve(fileHash);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ChunkInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.part")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                try {
                    result.add(new ChunkInfo(
                            Integer.parseInt(name.substring(0, name.length() - ".part".length())),
                            Files.size(p)));
                } catch (NumberFormatException ignored) {
                    // 非分片命名的文件（不该出现），跳过
                } catch (IOException ignored) {
                    // 扫描瞬间文件被清理，按"不存在"处理
                }
            }
        }
        result.sort(Comparator.comparingInt(ChunkInfo::index));
        return result;
    }

    @Override
    public void deleteAll(String fileHash) {
        LocalIo.deleteDirQuietly(chunksDir.resolve(fileHash));
    }

    /**
     * 清理"孤儿分片"目录：分片目录为叶子目录（无子目录）且最后修改时间早于 deadline。
     * 启用租户时布局为 chunks/{tenant}/{hash}，故递归到叶子层判断，删除后顺带清掉空父目录。
     */
    @Override
    public int deleteOrphansOlderThan(Instant deadline) {
        if (!Files.isDirectory(chunksDir)) {
            return 0;
        }
        int cleaned = 0;
        List<Path> leafDirs = new ArrayList<>();
        try (var walk = Files.walk(chunksDir)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(chunksDir))
                    .filter(LocalChunkStorage::isLeafDir)
                    .forEach(leafDirs::add);
        } catch (IOException e) {
            log.warn("扫描分片目录失败: {}", chunksDir, e);
            return 0;
        }
        for (Path dir : leafDirs) {
            try {
                if (Files.getLastModifiedTime(dir).toInstant().isBefore(deadline)) {
                    log.info("清理过期分片目录: {}", chunksDir.relativize(dir));
                    LocalIo.deleteDirQuietly(dir);
                    pruneEmptyParents(dir.getParent());
                    cleaned++;
                }
            } catch (IOException e) {
                log.warn("清理分片目录失败: {}", dir, e);
            }
        }
        return cleaned;
    }

    private static boolean isLeafDir(Path dir) {
        try (var children = Files.list(dir)) {
            return children.noneMatch(Files::isDirectory);
        } catch (IOException e) {
            return false;
        }
    }

    /** 删除后向上清理空租户目录（不越过 chunks 根） */
    private void pruneEmptyParents(Path dir) {
        while (dir != null && !dir.equals(chunksDir)) {
            try (var children = Files.list(dir)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                return;
            }
            dir = dir.getParent();
        }
    }
}
