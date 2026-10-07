package com.example.upload;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;

import com.example.upload.dto.CheckResponse;

import java.io.IOException;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户隔离端到端（app.upload.tenant.header=X-Tenant-Id，local 存储）：
 * 同一 hash 在不同租户域下互不可见——秒传、下载、删除、列表全部隔离；
 * 未带租户头 / 非法租户标识 → 400（显式失败，不静默归入默认租户）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIsolationTest {

    private static final Path BASE_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "upload-it-tenant-" + UUID.randomUUID());
    private static final String HEADER = "X-Tenant-Id";

    static final List<String> deletedEvents = new CopyOnWriteArrayList<>();

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.upload.base-dir", () -> BASE_DIR.toString());
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "json");
        registry.add("app.upload.tenant.header", () -> HEADER);
    }

    @BeforeEach
    void noThrowOnError() {
        rest.getRestTemplate().setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
    }

    @AfterAll
    static void wipeStorage() throws IOException {
        if (Files.isDirectory(BASE_DIR)) {
            try (var walk = Files.walk(BASE_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @Order(1)
    void missingOrInvalidTenant_rejected400() {
        ResponseEntity<Map> missing = postJson("/api/upload/check", Map.of("fileHash", "aaaaaaaaaaaaaaaa", "fileName", "x", "totalSize", 1, "totalChunks", 1, "chunkSize", 1), null);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat((String) missing.getBody().get("message")).contains("缺少租户请求头");

        ResponseEntity<Map> invalid = postJson("/api/upload/check", Map.of("fileHash", "aaaaaaaaaaaaaaaa", "fileName", "x", "totalSize", 1, "totalChunks", 1, "chunkSize", 1), "team A/..");
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat((String) invalid.getBody().get("message")).contains("租户标识非法");
    }

    @Test
    @Order(2)
    void sameHash_isIsolatedBetweenTenants() {
        byte[] c0 = fill('a', 64);
        byte[] c1 = fill('b', 64);
        byte[] total = concat(c0, c1);
        String hash = md5Hex(total);

        // teamA 完整上传并合并
        assertThat(check(hash, "teamA").finished()).isFalse();
        assertThat(uploadChunk(hash, 0, 2, c0, "teamA").getStatusCode().value()).isEqualTo(200);
        assertThat(uploadChunk(hash, 1, 2, c1, "teamA").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> merged = postJson("/api/upload/merge", mergeBody(hash, total.length), "teamA");
        assertThat(merged.getStatusCode().value()).isEqualTo(200);

        // teamA：秒传 + 列表可见 + 下载内容一致
        assertThat(check(hash, "teamA").finished()).isTrue();
        assertThat(listFiles("teamA")).anyMatch(f -> hash.equals(f.get("fileHash")));
        assertThat(download(hash, "teamA").getBody()).containsExactly(total);

        // teamB：同一 hash 完全不可见
        assertThat(check(hash, "teamB").finished()).isFalse();
        assertThat(check(hash, "teamB").uploadedChunks()).isEmpty();
        assertThat(download(hash, "teamB").getStatusCode().value()).isEqualTo(404);
        assertThat(listFiles("teamB")).noneMatch(f -> hash.equals(f.get("fileHash")));

        // teamB 不能删 teamA 的文件
        ResponseEntity<Map<String, Object>> deleted = rest.exchange("/api/files/" + hash, HttpMethod.DELETE,
                new HttpEntity<>(headers("teamB")), new ParameterizedTypeReference<>() {
                });
        assertThat(deleted.getBody()).containsEntry("deleted", false);
        assertThat(download(hash, "teamA").getStatusCode().value()).isEqualTo(200);

        // teamA 自己能删
        ResponseEntity<Map<String, Object>> selfDelete = rest.exchange("/api/files/" + hash, HttpMethod.DELETE,
                new HttpEntity<>(headers("teamA")), new ParameterizedTypeReference<>() {
                });
        assertThat(selfDelete.getBody()).containsEntry("deleted", true);
        assertThat(check(hash, "teamA").finished()).isFalse();
    }

    /* ---------------- 工具 ---------------- */

    private CheckResponse check(String hash, String tenant) {
        ResponseEntity<CheckResponse> resp = rest.exchange("/api/upload/check", HttpMethod.POST,
                new HttpEntity<>(Map.of("fileHash", hash, "fileName", "whatever.bin",
                        "totalSize", 128, "totalChunks", 2, "chunkSize", 64), headers(tenant)),
                CheckResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody();
    }

    private ResponseEntity<Map> postJson(String path, Map<String, Object> body, String tenant) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(tenant)), Map.class);
    }

    private Map<String, Object> mergeBody(String hash, long totalSize) {
        return Map.of("fileHash", hash, "fileName", "隔离测试.bin", "totalSize", totalSize, "totalChunks", 2, "chunkSize", 64);
    }

    private ResponseEntity<byte[]> download(String hash, String tenant) {
        return rest.exchange("/api/files/" + hash + "/download", HttpMethod.GET,
                new HttpEntity<>(headers(tenant)), byte[].class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listFiles(String tenant) {
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange("/api/files", HttpMethod.GET,
                new HttpEntity<>(headers(tenant)), new ParameterizedTypeReference<>() {
                });
        return resp.getBody();
    }

    private ResponseEntity<java.util.Map<String, Object>> uploadChunk(String hash, int index, int total, byte[] data, String tenant) {
        HttpHeaders headers = headers(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("fileHash", hash);
        body.add("chunkIndex", index);
        body.add("totalChunks", total);
        body.add("chunk", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return "chunk";
            }
        });
        return rest.exchange("/api/upload/chunk", HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    private HttpHeaders headers(String tenant) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.ALL));
        if (tenant != null) {
            h.set(HEADER, tenant);
        }
        return h;
    }

    private static byte[] fill(char c, int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) c);
        return data;
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] p : parts) {
            size += p.length;
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest.digest(data)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
