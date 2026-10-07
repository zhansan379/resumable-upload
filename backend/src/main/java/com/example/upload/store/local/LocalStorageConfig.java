package com.example.upload.store.local;

import com.example.upload.config.UploadProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 本地磁盘后端装配：app.upload.storage.type = local（默认）时生效。
 * 未来 S3 后端在此并列一个 S3StorageConfig（havingValue = "s3"），协议层无感。
 */
@Configuration
public class LocalStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.upload.storage.type", havingValue = "local", matchIfMissing = true)
    public LocalChunkStorage localChunkStorage(UploadProperties props) throws IOException {
        return new LocalChunkStorage(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.upload.storage.type", havingValue = "local", matchIfMissing = true)
    public LocalFileStorage localFileStorage(UploadProperties props) throws IOException {
        return new LocalFileStorage(props);
    }
}
