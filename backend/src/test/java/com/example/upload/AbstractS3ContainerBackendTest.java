package com.example.upload;

import com.example.upload.store.spi.ChunkStorage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基于 S3 兼容容器（MinIO / RustFS / ...）的后端测试公共部分：
 * 两个 S3 专属语义用例——非末片 ≥ 5MiB 的前置拒绝、取消上传中止 multipart 会话。
 * 容器配置（镜像/凭证）由子类各自完成；建桶交给应用自身的 ensureBucket 启动逻辑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractS3ContainerBackendTest extends AbstractUploadBackendTest {

    @Autowired
    ChunkStorage chunkStorage; // type=s3 时的唯一 ChunkStorage 实现（S3UploadStorage）

    /** 子类在 @DynamicPropertySource 中调用：注册 type=s3 全套配置 */
    protected static void registerS3Props(DynamicPropertyRegistry registry, String host, int port,
                                          String user, String password, String bucket) {
        baseDir = Path.of(System.getProperty("java.io.tmpdir"), "upload-it-s3-" + UUID.randomUUID());
        registry.add("app.upload.storage.type", () -> "s3");
        registry.add("app.upload.base-dir", baseDir::toString);
        registry.add("app.upload.storage.s3.endpoint", () -> "http://" + host + ":" + port);
        registry.add("app.upload.storage.s3.region", () -> "us-east-1");
        registry.add("app.upload.storage.s3.bucket", () -> bucket);
        registry.add("app.upload.storage.s3.access-key", () -> user);
        registry.add("app.upload.storage.s3.secret-key", () -> password);
        registry.add("app.upload.storage.s3.key-prefix", () -> "files");
        registry.add("app.upload.storage.s3.path-style-access", () -> "true");
    }

    @Test
    @Order(10)
    void s3_nonLastPartBelow5MiB_rejected400() {
        String hash = md5Hex(("s3-small-part-" + containerTag()).getBytes(StandardCharsets.UTF_8));
        // 非末片 1KB < 5MiB：能力声明前置拒绝，不产生任何分片状态
        ResponseEntity<Map> resp = uploadChunkRawAsMap(hash, 0, 3, fill('x', 1024));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("非末片分片至少");

        // 末片不受限制
        assertThat(uploadChunkRaw(hash, 2, 3, fill('y', 1024)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @Order(11)
    void s3_cancelUpload_abortsMultipartSession() throws Exception {
        String hash = md5Hex(("s3-cancel-abort-" + containerTag()).getBytes(StandardCharsets.UTF_8));
        // 非末片 5MiB + 末片小片：合法组合
        assertThat(uploadChunkRaw(hash, 0, 2, fill('a', NON_LAST)).getStatusCode().value()).isEqualTo(200);
        assertThat(uploadChunkRaw(hash, 1, 2, fill('b', 1024)).getStatusCode().value()).isEqualTo(200);
        assertThat(chunkStorage.list(hash)).hasSize(2);

        ResponseEntity<Map<String, Object>> cleaned = rest.exchange("/api/upload/" + hash, HttpMethod.DELETE, null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(cleaned.getBody()).containsEntry("cleaned", true);

        // abort 会话后分片随之消失，合并报告缺片（409）而非半途数据
        assertThat(chunkStorage.list(hash)).isEmpty();
        ResponseEntity<Map> merged = rest.postForEntity("/api/upload/merge",
                json(Map.of("fileHash", hash, "fileName", "取消后合并.bin",
                        "totalSize", (long) NON_LAST + 1024, "totalChunks", 2, "chunkSize", NON_LAST)),
                Map.class);
        assertThat(merged.getStatusCode().value()).isEqualTo(409);
    }

    /** 子类返回容器标识，用于生成互不冲突的测试 hash */
    protected abstract String containerTag();
}
