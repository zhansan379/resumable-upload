package com.example.upload;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S3 后端集成测试（真实 MinIO 容器），额外覆盖预签名直传端到端。 */
public class S3BackendTest extends AbstractS3ContainerBackendTest {

    private static final String ROOT_USER = "minioadmin";
    private static final String ROOT_PASSWORD = "minioadmin-resumable";
    private static final String BUCKET = "resumable-upload-it";

    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3Props(DynamicPropertyRegistry registry) {
        registerS3Props(registry, MINIO.getHost(), MINIO.getMappedPort(9000),
                ROOT_USER, ROOT_PASSWORD, BUCKET);
        // MinIO 套件额外验证预签名直传端到端（RustFS 套件未覆盖 presign 兼容性）
        registry.add("app.upload.storage.s3.direct-upload", () -> "true");
    }

    @Override
    protected String containerTag() {
        return "minio";
    }

    /* ---------------- 预签名直传端到端 ---------------- */

    @Test
    @Order(20)
    void s3_directUpload_endToEnd() throws Exception {
        // 数据不能与抽象套件的用例撞 hash（同内容同 hash 会直接秒传命中）
        byte[] p0 = fill('c', NON_LAST);
        byte[] p1 = fill('d', 100);
        byte[] total = concat(p0, p1);
        String hash = md5Hex(total);

        // 1. init：创建会话，返回 uploadId 与非末片最小字节数
        ResponseEntity<com.example.upload.dto.DirectInitResponse> init = rest.postForEntity(
                "/api/upload/direct/init",
                json(Map.of("fileHash", hash, "fileName", "direct.bin",
                        "totalSize", (long) total.length, "totalChunks", 2)),
                com.example.upload.dto.DirectInitResponse.class);
        assertThat(init.getStatusCode().value()).isEqualTo(200);
        assertThat(init.getBody().finished()).isFalse();
        assertThat(init.getBody().uploadId()).isNotBlank();
        assertThat(init.getBody().minNonLastPartSize()).isEqualTo((long) NON_LAST);

        // 2. 批量取预签名地址
        ResponseEntity<com.example.upload.dto.DirectPartUrlsResponse> urls = rest.postForEntity(
                "/api/upload/direct/part-urls",
                json(Map.of("fileHash", hash, "uploadId", init.getBody().uploadId(), "chunkIndexes", List.of(0, 1))),
                com.example.upload.dto.DirectPartUrlsResponse.class);
        assertThat(urls.getStatusCode().value()).isEqualTo(200);
        assertThat(urls.getBody().urls()).hasSize(2);

        // 3. 浏览器视角：直接 PUT 到对象存储（不经过后端）
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            byte[] body = i == 0 ? p0 : p1;
            String url = urls.getBody().urls().stream()
                    .filter(u -> u.chunkIndex() == idx).findFirst().orElseThrow().url();
            HttpResponse<String> put = client.send(HttpRequest.newBuilder(URI.create(url))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).isEqualTo(200);
        }

        // 4. 复用既有合并端点（服务端 ListParts 自取 ETag，客户端无需回传）
        ResponseEntity<com.example.upload.dto.MergeResponse> merged = rest.postForEntity("/api/upload/merge",
                json(Map.of("fileHash", hash, "fileName", "direct.bin",
                        "totalSize", (long) total.length, "totalChunks", 2, "chunkSize", NON_LAST)),
                com.example.upload.dto.MergeResponse.class);
        assertThat(merged.getStatusCode().value()).isEqualTo(200);

        // 5. 下载内容逐字节一致
        assertThat(rest.getForEntity("/api/files/" + hash + "/download", byte[].class).getBody())
                .containsExactly(total);

        // 6. 再次 init → 秒传命中
        ResponseEntity<com.example.upload.dto.DirectInitResponse> again = rest.postForEntity(
                "/api/upload/direct/init",
                json(Map.of("fileHash", hash, "fileName", "direct.bin",
                        "totalSize", (long) total.length, "totalChunks", 2)),
                com.example.upload.dto.DirectInitResponse.class);
        assertThat(again.getBody().finished()).isTrue();

        // 7. 篡改 uploadId → 409（不为失效会话签发 URL）
        ResponseEntity<Map> bad = rest.postForEntity("/api/upload/direct/part-urls",
                json(Map.of("fileHash", hash, "uploadId", "fake-upload-id", "chunkIndexes", List.of(0))),
                Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(409);
    }
}
