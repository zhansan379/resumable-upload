package com.example.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 本地磁盘后端（默认）全链路集成测试。 */
public class LocalStorageBackendTest extends AbstractUploadBackendTest {

    @DynamicPropertySource
    static void localProps(DynamicPropertyRegistry registry) {
        baseDir = Path.of(System.getProperty("java.io.tmpdir"), "upload-it-local-" + UUID.randomUUID());
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "json");
        registry.add("app.upload.base-dir", baseDir::toString);
    }
}
