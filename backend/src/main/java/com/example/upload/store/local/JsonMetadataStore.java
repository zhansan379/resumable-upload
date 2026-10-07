package com.example.upload.store.local;

import com.example.upload.config.UploadProperties;
import com.example.upload.store.FileRecord;
import com.example.upload.store.spi.MetadataStore;
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
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 index.json 的秒传索引（默认元数据实现）。
 * 事实源是存储端：index.json 仅用于秒传查询与文件列表；文件存在与否以 FileStorage.stat 为准。
 * 写入采用"临时文件 + 原子移动"，避免进程中途挂掉留下损坏的索引。
 * 多实例部署时请换 JDBC 实现（JSON 文件无法共享）。
 */
@Component
public class JsonMetadataStore implements MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(JsonMetadataStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, FileRecord> map = new ConcurrentHashMap<>();

    public JsonMetadataStore(UploadProperties props) {
        this.file = Path.of(props.getBaseDir()).toAbsolutePath().normalize().resolve("index.json");
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

    @Override
    public synchronized void put(FileRecord rec) {
        map.put(rec.getFileHash(), rec);
        flush();
    }

    @Override
    public synchronized FileRecord get(String hash) {
        return map.get(hash);
    }

    @Override
    public synchronized boolean containsKey(String hash) {
        return map.containsKey(hash);
    }

    @Override
    public synchronized void updateVerified(String hash, boolean verified) {
        FileRecord r = map.get(hash);
        if (r != null && r.isVerified() != verified) {
            r.setVerified(verified);
            flush();
        }
    }

    @Override
    public synchronized boolean remove(String hash) {
        boolean removed = map.remove(hash) != null;
        if (removed) {
            flush();
        }
        return removed;
    }

    @Override
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
