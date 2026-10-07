package com.example.upload.store.mybatis;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus 实体（type=mybatis-plus 时使用）：与纯 MyBatis 版共享 upload_file_record 表结构。
 */
@TableName("upload_file_record")
public class UploadFileRecordEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("file_hash")
    private String fileHash;

    @TableField("file_name")
    private String fileName;

    @TableField("size")
    private Long size;

    @TableField("stored_path")
    private String storedPath;

    @TableField("upload_time")
    private Long uploadTime;

    @TableField("verified")
    private Boolean verified;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public void setStoredPath(String storedPath) {
        this.storedPath = storedPath;
    }

    public Long getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(Long uploadTime) {
        this.uploadTime = uploadTime;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }
}
