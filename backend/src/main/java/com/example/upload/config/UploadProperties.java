package com.example.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上传相关配置项，见 application.yml 中 app.upload 前缀。
 */
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 上传根目录 */
    private String baseDir = "./data/upload";

    /** 合并完成后是否异步重算 MD5 校验完整性 */
    private boolean verifyMd5AfterMerge = true;

    /** 单文件总大小上限（支持 GB/MB 单位） */
    private String maxTotalSize = "20GB";

    /** 分片数量上限 */
    private int maxTotalChunks = 100_000;

    /** 孤儿分片目录保留时长（客户端消失后无取消请求，超过该时长的分片目录被定时清理） */
    private Duration chunkTtl = Duration.ofHours(24);

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public boolean isVerifyMd5AfterMerge() {
        return verifyMd5AfterMerge;
    }

    public void setVerifyMd5AfterMerge(boolean verifyMd5AfterMerge) {
        this.verifyMd5AfterMerge = verifyMd5AfterMerge;
    }

    public String getMaxTotalSize() {
        return maxTotalSize;
    }

    public void setMaxTotalSize(String maxTotalSize) {
        this.maxTotalSize = maxTotalSize;
    }

    public int getMaxTotalChunks() {
        return maxTotalChunks;
    }

    public void setMaxTotalChunks(int maxTotalChunks) {
        this.maxTotalChunks = maxTotalChunks;
    }

    public Duration getChunkTtl() {
        return chunkTtl;
    }

    public void setChunkTtl(Duration chunkTtl) {
        this.chunkTtl = chunkTtl;
    }
}
