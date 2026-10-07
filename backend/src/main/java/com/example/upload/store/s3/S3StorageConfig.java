package com.example.upload.store.s3;

import com.example.upload.config.UploadProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Objects;

/**
 * S3 兼容后端装配：app.upload.storage.type = s3 时生效。
 * 一个 S3UploadStorage 同时实现 ChunkStorage 与 FileStorage（共享 multipart 会话状态）；
 * 秒传索引走 MetadataStore（json / mybatis / mybatis-plus，见 MetadataStoreConfig）。
 * 开启 s3.direct-upload 时额外装配 S3Presigner 供预签名直传使用。
 */
@Configuration
@ConditionalOnProperty(name = "app.upload.storage.type", havingValue = "s3")
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(UploadProperties props) {
        return configure(S3Client.builder(), props).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.upload.storage.s3.direct-upload", havingValue = "true")
    public S3Presigner s3Presigner(UploadProperties props) {
        UploadProperties.S3 cfg = props.getStorage().getS3();
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())));
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(cfg.isPathStyleAccess())
                .build());
        return builder.build();
    }

    @Bean
    public S3UploadStorage s3UploadStorage(S3Client s3Client, UploadProperties props,
                                           ObjectProvider<S3Presigner> presigner) {
        S3UploadStorage storage = new S3UploadStorage(s3Client, props, presigner.getIfAvailable());
        storage.ensureBucket();
        return storage;
    }

    /** S3Client 与 S3Presigner 共用的连接配置 */
    private S3ClientBuilder configure(S3ClientBuilder builder, UploadProperties props) {
        UploadProperties.S3 cfg = props.getStorage().getS3();
        if (cfg.getBucket() == null || cfg.getBucket().isBlank()) {
            throw new IllegalStateException("app.upload.storage.s3.bucket 未配置（type=s3 时必填）");
        }
        builder.region(Region.of(Objects.requireNonNullElse(cfg.getRegion(), "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .forcePathStyle(cfg.isPathStyleAccess());
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        return builder;
    }
}
