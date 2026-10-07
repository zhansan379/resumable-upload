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

    private final Storage storage = new Storage();

    /** 存储后端配置 */
    public Storage getStorage() {
        return storage;
    }

    public static class Storage {

        /** 存储后端类型：local（本地磁盘，默认）| s3（S3 兼容对象存储） */
        private String type = "local";

        private final S3 s3 = new S3();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        /** S3 后端配置（type = s3 时生效） */
        public S3 getS3() {
            return s3;
        }
    }

    public static class S3 {

        /** S3 兼容端点（MinIO/RustFS/OSS/COS/OBS 的 S3 地址）；留空则使用 AWS 默认端点 */
        private String endpoint = "";

        /** 区域（MinIO/RustFS 任意值即可，如 us-east-1） */
        private String region = "us-east-1";

        /** 存储桶名（必填） */
        private String bucket = "";

        /** 访问密钥 */
        private String accessKey = "";

        /** 私密密钥 */
        private String secretKey = "";

        /** 对象 key 前缀（命名空间隔离），如 "files"；最终 key = {prefix}/{fileHash} */
        private String keyPrefix = "files";

        /** 路径风格寻址（MinIO/RustFS 必须为 true；AWS 原生为 false） */
        private boolean pathStyleAccess = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }
    }
}
