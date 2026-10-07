package com.example.upload.store.mybatis;

import com.example.upload.store.FileRecord;
import com.example.upload.store.spi.MetadataStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 纯 MyBatis 元数据实现（type=mybatis）。多实例部署用：秒传索引进宿主的关系库。
 * 空租户统一存空串（NOT NULL 列），读回转 null，与"未启用租户"语义对应。
 */
public class MybatisMetadataStore implements MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisMetadataStore.class);

    private final UploadFileRecordMapper mapper;

    public MybatisMetadataStore(UploadFileRecordMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        mapper.createTable();
        log.info("元数据表 upload_file_record 就绪");
    }

    @Override
    public FileRecord get(String tenant, String hash) {
        return normalize(mapper.selectOne(tenantId(tenant), hash));
    }

    @Override
    public boolean containsKey(String tenant, String hash) {
        return mapper.exists(tenantId(tenant), hash);
    }

    @Override
    public void put(FileRecord rec) {
        // 写库副本：null 租户归一为空串（NOT NULL 列），不改调用方传入的对象
        FileRecord write = forWrite(rec);
        if (mapper.update(write) == 0) {
            try {
                mapper.insert(write);
            } catch (RuntimeException e) {
                // 并发插入撞主键：重试一次更新（另一实例可能刚插入）
                if (mapper.update(write) == 0) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void updateVerified(String tenant, String hash, boolean verified) {
        mapper.updateVerified(tenantId(tenant), hash, verified);
    }

    @Override
    public boolean remove(String tenant, String hash) {
        return mapper.delete(tenantId(tenant), hash) > 0;
    }

    @Override
    public List<FileRecord> all() {
        return mapper.selectAll().stream().map(this::normalize).toList();
    }

    private FileRecord normalize(FileRecord rec) {
        if (rec == null) {
            return null;
        }
        if (rec.getTenant() != null && rec.getTenant().isEmpty()) {
            rec.setTenant(null);
        }
        return rec;
    }

    private FileRecord forWrite(FileRecord rec) {
        return new FileRecord(tenantId(rec.getTenant()), rec.getFileHash(), rec.getFileName(),
                rec.getSize(), rec.getStoredPath(), rec.getUploadTime(), rec.isVerified());
    }

    private String tenantId(String tenant) {
        return tenant == null ? "" : tenant;
    }
}
