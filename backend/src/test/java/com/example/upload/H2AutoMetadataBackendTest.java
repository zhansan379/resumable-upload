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

/**
 * 元数据 type=auto（默认）：测试类路径同时有 MyBatis-Plus 与 MyBatis，
 * 探测按优先级选中 MyBatis-Plus 实现。宿主两样都没有时 auto 回落 JSON（无法在同 JVM 模拟，见 docs/07）。
 * 注意：auto 模式探测到 MyBatis 系后要求宿主配置了数据源，否则启动失败——不打算用库请显式 type=json。
 */
public class H2AutoMetadataBackendTest extends AbstractUploadBackendTest {

    @Autowired
    MetadataStore metadataStore;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseDir = Path.of(System.getProperty("java.io.tmpdir"), "upload-it-h2-auto-" + UUID.randomUUID());
        registry.add("app.upload.base-dir", baseDir::toString);
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "auto");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:uploadit-auto-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Test
    @Order(20)
    void autoDetectsMybatisPlusByPriority() {
        assertThat(metadataStore).isInstanceOf(MybatisPlusMetadataStore.class);
    }
}
