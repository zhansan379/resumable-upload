package com.example.upload;

import com.example.upload.store.spi.MetadataStore;
import com.example.upload.store.mybatis.MybatisPlusMetadataStore;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据走 MyBatis-Plus 实现（H2 内存库）：BaseMapper/LambdaWrapper + 合并租约锁。 */
public class H2MybatisPlusBackendTest extends AbstractUploadBackendTest {

    @Autowired
    MetadataStore metadataStore;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseDir = Path.of(System.getProperty("java.io.tmpdir"), "upload-it-h2-mp-" + UUID.randomUUID());
        registry.add("app.upload.base-dir", baseDir::toString);
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "mybatis-plus");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:uploadit-mp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Test
    @Order(20)
    void mybatisPlusImplSelected() {
        assertThat(metadataStore).isInstanceOf(MybatisPlusMetadataStore.class);
    }
}
