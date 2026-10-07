package com.example.upload;

import com.example.upload.service.TenantResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户来源可插拔（集成方视角的解耦验证）：
 * 宿主注册自己的 TenantResolver（此处模拟企业租户头 X-Corp-Tenant，缺省租户 guest）后，
 * 组件默认的头实现与 app.upload.tenant.header 配置完全退位；
 * 租户字符集校验仍集中在协议层，宿主实现无法绕过路径注入防护。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostTenantResolverTest {

    private static final Path BASE_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "upload-it-corp-tenant-" + UUID.randomUUID());
    private static final String CORP_HEADER = "X-Corp-Tenant";

    @Autowired
    TestRestTemplate rest;

    @TestConfiguration
    static class CorpTenantConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        TenantResolver corpTenantResolver() {
            // 模拟宿主已有租户体系：从企业自己的头取租户，缺失时落到 guest 域
            // （@Primary 为指南推荐写法：即使默认头实现同时被装配，注入也无歧义）
            return request -> {
                String v = request.getHeader(CORP_HEADER);
                return (v == null || v.isBlank()) ? "guest" : v;
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.upload.base-dir", () -> BASE_DIR.toString());
        registry.add("app.upload.storage.type", () -> "local");
        registry.add("app.upload.metadata.type", () -> "json");
        // 注意：tenant.header 保持默认（未启用）——租户完全由宿主 resolver 提供
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
    void hostResolver_takesOverTenantSource() {
        byte[] c0 = fill('a', 64);
        byte[] c1 = fill('b', 64);
        byte[] total = concat(c0, c1);
        String hash = md5Hex(total);

        // 宿主租户域 acme：上传 + 合并
        assertThat(uploadChunk(hash, 0, 2, c0, "acme").getStatusCode().value()).isEqualTo(200);
        assertThat(uploadChunk(hash, 1, 2, c1, "acme").getStatusCode().value()).isEqualTo(200);
        assertThat(merge(hash, total.length, "acme").getStatusCode().value()).isEqualTo(200);

        // acme 可见；beta 不可见；guest（无企业头）不可见
        assertThat(listFiles("acme")).anyMatch(f -> hash.equals(f.get("fileHash")));
        assertThat(listFiles("beta")).noneMatch(f -> hash.equals(f.get("fileHash")));
        assertThat(listFiles(null)).noneMatch(f -> hash.equals(f.get("fileHash")));
        assertThat(download(hash, "acme").getBody()).containsExactly(total);
        assertThat(download(hash, "beta").getStatusCode().value()).isEqualTo(404);

        // 组件自带的 X-Tenant-Id 头被无视（宿主 resolver 不读它）：带上也不进 acme 之外的域
        HttpHeaders legacy = new HttpHeaders();
        legacy.set("X-Tenant-Id", "acme");
        assertThat(rest.exchange("/api/files/" + hash + "/download", HttpMethod.GET,
                new HttpEntity<>(legacy), byte[].class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void charsetValidation_cannotBeBypassedByHostResolver() {
        // 宿主 resolver 返回什么协议层都做字符集校验（租户会进存储路径）
        ResponseEntity<Map> resp = rest.exchange("/api/files", HttpMethod.GET,
                new HttpEntity<>(headers("../evil")), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("租户标识非法");
    }

    /* ---------------- 工具 ---------------- */

    private ResponseEntity<Map> merge(String hash, long totalSize, String tenant) {
        return rest.exchange("/api/upload/merge", HttpMethod.POST,
                new HttpEntity<>(Map.of("fileHash", hash, "fileName", "企业租户.bin",
                        "totalSize", totalSize, "totalChunks", 2, "chunkSize", 64), headers(tenant)),
                Map.class);
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

    private ResponseEntity<Map<String, Object>> uploadChunk(String hash, int index, int total, byte[] data, String tenant) {
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
            h.set(CORP_HEADER, tenant);
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
