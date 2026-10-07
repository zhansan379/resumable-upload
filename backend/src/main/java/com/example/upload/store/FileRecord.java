package com.example.upload.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * hash 索引条目（持久化到 index.json）。
 * fileHash -> 已合并文件的关系即"秒传"依据，服务端重启后不丢失。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileRecord {

    private String fileHash;
    /** 租户标识（未启用租户时为 null）；秒传与列表按租户分域 */
    private String tenant;
    private String fileName;
    private long size;
    /** 相对于上传根目录的存储路径 */
    private String storedPath;
    private long uploadTime;
    /** 合并后异步重算 MD5 是否通过 */
    private boolean verified;

    public FileRecord() {
    }

    public FileRecord(String fileHash, String fileName, long size, String storedPath, long uploadTime, boolean verified) {
        this(null, fileHash, fileName, size, storedPath, uploadTime, verified);
    }

    public FileRecord(String tenant, String fileHash, String fileName, long size, String storedPath,
                      long uploadTime, boolean verified) {
        this.tenant = tenant;
        this.fileHash = fileHash;
        this.fileName = fileName;
        this.size = size;
        this.storedPath = storedPath;
        this.uploadTime = uploadTime;
        this.verified = verified;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public void setStoredPath(String storedPath) {
        this.storedPath = storedPath;
    }

    public long getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }
}
