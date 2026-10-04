package com.example.upload.store;

import com.example.upload.config.UploadProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件 hash 索引（fileHash -> 已合并文件记录）。
 * 事实源是磁盘：index.json 仅用于"秒传"查询与文件列表；合并后的真实文件存在与否以文件系统为准。
 * 写入采用"临时文件 + 原子移动"，避免进程中途挂掉留下损坏的索引。
 */
@Component
public class HashIndexStore {

    private static final Logger log = LoggerFactory.getLogger(HashIndexStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, FileRecord> map = new ConcurrentHashMap<>();

    public HashIndexStore(UploadProperties props) {
        this.file = Paths.get(props.getBaseDir()).toAbsolutePath().normalize().resolve("index.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            FileRecord[] arr = mapper.readValue(file.toFile(), FileRecord[].class);
            for (FileRecord r : arr) {
                map.put(r.getFileHash(), r);
            }
            log.info("已加载 hash 索引 {} 条", map.size());
        } catch (IOException e) {
            log.warn("hash 索引加载失败，按空索引启动: {}", file, e);
        }
    }

    public synchronized void put(FileRecord rec) {
        map.put(rec.getFileHash(), rec);
        flush();
    }

    public synchronized FileRecord get(String hash) {
        return map.get(hash);
    }

    public synchronized boolean containsKey(String hash) {
        return map.containsKey(hash);
    }

    public synchronized void updateVerified(String hash, boolean verified) {
        FileRecord r = map.get(hash);
        if (r != null && r.isVerified() != verified) {
            r.setVerified(verified);
            flush();
        }
    }

    public synchronized boolean remove(String hash) {
        boolean removed = map.remove(hash) != null;
        if (removed) {
            flush();
        }
        return removed;
    }

    public synchronized List<FileRecord> all() {
        return List.copyOf(map.values());
    }

    private void flush() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, map.values());
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 hash 索引失败", e);
        }
    }
}
