package com.example.upload.config;

import com.example.upload.store.s3.S3StorageConfig;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;

/**
 * 预签名直传端点开关：type=s3 且 s3.direct-upload=true 时，
 * DirectUploadService / DirectUploadController / S3Presigner 才装配。
 * 参见 {@link S3StorageConfig}。
 */
public class DirectUploadEnabled extends AllNestedConditions {

    public DirectUploadEnabled() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "app.upload.storage.type", havingValue = "s3")
    static class OnS3Storage {
    }

    @ConditionalOnProperty(name = "app.upload.storage.s3.direct-upload", havingValue = "true")
    static class OnFlagEnabled {
    }
}
