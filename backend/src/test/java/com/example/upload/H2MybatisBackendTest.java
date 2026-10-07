package com.example.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

/** 元数据走纯 MyBatis 实现（H2 内存库）：验证注解 Mapper + 自动建表 + 合并租约锁。 */
public class H2MybatisBackendTest extends AbstractUploadBackendTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseDir = Path.of(System.getProperty("java.io.tmpdir"), "upload-it-h2-mb-" + UUID.randomUUID());
        registry.add("app.upload.base-dir", baseDir::toString);
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "mybatis");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:uploadit-mb-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }
}
