package com.example.upload.store.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.upload.store.FileRecord;
import com.example.upload.store.spi.MetadataStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MyBatis-Plus 元数据实现（type=mybatis-plus）：BaseMapper + Lambda 条件构造，
 * 供 MP 宿主遵循其习惯用法；与纯 MyBatis 版共享表结构，可互换。
 */
public class MybatisPlusMetadataStore implements MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisPlusMetadataStore.class);

    private final UploadFileRecordPlusMapper mapper;

    public MybatisPlusMetadataStore(UploadFileRecordPlusMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        mapper.createTable();
        log.info("元数据表 upload_file_record 就绪");
    }

    @Override
    public FileRecord get(String tenant, String hash) {
        UploadFileRecordEntity entity = mapper.selectOne(wrapper(tenant, hash));
        return entity == null ? null : normalize(toRecord(entity));
    }

    @Override
    public boolean containsKey(String tenant, String hash) {
        return mapper.exists(wrapper(tenant, hash));
    }

    @Override
    public void put(FileRecord rec) {
        UploadFileRecordEntity entity = toEntity(rec);
        LambdaQueryWrapper<UploadFileRecordEntity> query = wrapper(rec.getTenant(), rec.getFileHash());
        if (mapper.update(null, new UpdateWrapper<UploadFileRecordEntity>()
                .eq("tenant_id", entity.getTenantId()).eq("file_hash", entity.getFileHash())
                .set("file_name", entity.getFileName()).set("size", entity.getSize())
                .set("stored_path", entity.getStoredPath()).set("upload_time", entity.getUploadTime())
                .set("verified", entity.getVerified())) == 0) {
            try {
                mapper.insert(entity);
            } catch (RuntimeException e) {
                // 并发插入撞主键：重试一次更新（另一实例可能刚插入）
                if (mapper.update(null, new UpdateWrapper<UploadFileRecordEntity>()
                        .eq("tenant_id", entity.getTenantId()).eq("file_hash", entity.getFileHash())
                        .set("file_name", entity.getFileName()).set("size", entity.getSize())
                        .set("stored_path", entity.getStoredPath()).set("upload_time", entity.getUploadTime())
                        .set("verified", entity.getVerified())) == 0) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void updateVerified(String tenant, String hash, boolean verified) {
        mapper.update(null, new LambdaUpdateWrapper<UploadFileRecordEntity>()
                .eq(UploadFileRecordEntity::getTenantId, tenantId(tenant))
                .eq(UploadFileRecordEntity::getFileHash, hash)
                .set(UploadFileRecordEntity::getVerified, verified));
    }

    @Override
    public boolean remove(String tenant, String hash) {
        return mapper.delete(wrapper(tenant, hash)) > 0;
    }

    @Override
    public List<FileRecord> all() {
        return mapper.selectList(null).stream().map(e -> normalize(toRecord(e))).toList();
    }

    private LambdaQueryWrapper<UploadFileRecordEntity> wrapper(String tenant, String hash) {
        return new LambdaQueryWrapper<UploadFileRecordEntity>()
                .eq(UploadFileRecordEntity::getTenantId, tenantId(tenant))
                .eq(UploadFileRecordEntity::getFileHash, hash);
    }

    private UploadFileRecordEntity toEntity(FileRecord rec) {
        UploadFileRecordEntity e = new UploadFileRecordEntity();
        e.setTenantId(tenantId(rec.getTenant()));
        e.setFileHash(rec.getFileHash());
        e.setFileName(rec.getFileName());
        e.setSize(rec.getSize());
        e.setStoredPath(rec.getStoredPath());
        e.setUploadTime(rec.getUploadTime());
        e.setVerified(rec.isVerified());
        return e;
    }

    private FileRecord toRecord(UploadFileRecordEntity e) {
        return new FileRecord(nullableTenant(e.getTenantId()), e.getFileHash(), e.getFileName(),
                e.getSize() == null ? 0 : e.getSize(), e.getStoredPath(),
                e.getUploadTime() == null ? 0 : e.getUploadTime(),
                Boolean.TRUE.equals(e.getVerified()));
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

    private String tenantId(String tenant) {
        return tenant == null ? "" : tenant;
    }

    private String nullableTenant(String tenantId) {
        return tenantId == null || tenantId.isEmpty() ? null : tenantId;
    }
}
