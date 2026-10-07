package com.example.upload.store.s3;

import com.example.upload.config.UploadProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * S3 兼容后端装配：app.upload.storage.type = s3 时生效。
 * 一个 S3UploadStorage 同时实现 ChunkStorage 与 FileStorage（共享 multipart 会话状态）；
 * 秒传索引仍走 MetadataStore（默认 JSON，多实例部署换 JDBC）。
 */
@Configuration
@ConditionalOnProperty(name = "app.upload.storage.type", havingValue = "s3")
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(UploadProperties props) {
        UploadProperties.S3 cfg = props.getStorage().getS3();
        if (cfg.getBucket() == null || cfg.getBucket().isBlank()) {
            throw new IllegalStateException("app.upload.storage.s3.bucket 未配置（type=s3 时必填）");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .forcePathStyle(cfg.isPathStyleAccess());
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    public S3UploadStorage s3UploadStorage(S3Client s3Client, UploadProperties props) {
        S3UploadStorage storage = new S3UploadStorage(s3Client, props);
        storage.ensureBucket();
        return storage;
    }
}
