package com.example.upload;

import com.example.upload.dto.CheckResponse;
import com.example.upload.dto.ChunkSavedResponse;
import com.example.upload.dto.MergeResponse;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储后端无关的全链路集成测试（协议行为安全网）：
 * check → 分片 → 合并 → 秒传 → 全量/Range/If-Range 下载 → 删除，
 * 以及错误语义（400 参数 / 409 缺片与总字节不符 / 416 区间越界）与事件 SPI 回调。
 * 由 LocalStorageBackendTest（本地磁盘）与 S3BackendTest（MinIO 对象存储）两套后端共同执行。
 * <p>
 * 分片数据统一按 S3 约束构造（非末片 ≥ 5MiB），本地后端同样适用；
 * 5MiB 下限的前置拒绝本身由 S3BackendTest 的专属用例覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractUploadBackendTest {

    /** S3 multipart 硬约束：非末片最小 5MiB。测试分片布局以此为准，两个后端通用 */
    protected static final int NON_LAST = 5 * 1024 * 1024;

    /** 子类在 @DynamicPropertySource 里赋值：元数据（index.json）落盘目录 */
    protected static Path baseDir;

    /** 事件 SPI 录制结果由 test 源码下的 {@link RecordingUploadListener}（组件扫描注册）填充 */
    static final List<String> mergeEvents = RecordingUploadListener.mergeEvents;
    static final List<String> deletedEvents = RecordingUploadListener.deletedEvents;
    static final Map<String, Boolean> verifyEvents = RecordingUploadListener.verifyEvents;

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void returnErrorStatusInsteadOfThrowing() {
        // 断言的是协议状态码本身，禁用 RestTemplate 的 4xx/5xx 抛异常行为
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
        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (var walk = Files.walk(baseDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /* ---------------- 主链路 ---------------- */

    @Test
    @Order(1)
    void fullChain_checkChunkMergeInstantResume_rangeDownload_delete() {
        byte[] c0 = fill('a', NON_LAST);
        byte[] c1 = fill('b', NON_LAST);
        byte[] c2 = fill('c', 50);
        byte[] total = concat(c0, c1, c2);
        String hash = md5Hex(total);
        String fileName = "集成测试.bin";

        // 1. 首次 check：非秒传，无已传分片
        CheckResponse check1 = check(hash);
        assertThat(check1.finished()).isFalse();
        assertThat(check1.uploadedChunks()).isEmpty();

        // 2. 三片上传（前两片 5MiB，末片 50 字节）
        for (int i = 0; i < 3; i++) {
            ChunkSavedResponse saved = uploadChunk(hash, i, 3, switch (i) {
                case 0 -> c0;
                case 1 -> c1;
                default -> c2;
            });
            assertThat(saved.chunkIndex()).isEqualTo(i);
            assertThat(saved.size()).isEqualTo(switch (i) {
                case 0 -> (long) NON_LAST;
                case 1 -> (long) NON_LAST;
                default -> 50L;
            });
        }

        // 3. 断点续传探测：三片都在
        assertThat(check(hash).uploadedChunks()).containsExactly(0, 1, 2);

        // 4. 合并
        ResponseEntity<MergeResponse> merged = rest.postForEntity("/api/upload/merge",
                json(Map.of("fileHash", hash, "fileName", fileName,
                        "totalSize", (long) total.length, "totalChunks", 3, "chunkSize", NON_LAST)),
                MergeResponse.class);
        assertThat(merged.getStatusCode().value()).isEqualTo(200);
        assertThat(merged.getBody()).isNotNull();
        assertThat(merged.getBody().fileHash()).isEqualTo(hash);
        assertThat(merged.getBody().size()).isEqualTo((long) total.length);
        assertThat(merged.getBody().url()).isEqualTo("/api/files/" + hash + "/download");

        // 5. 事件 SPI：合并完成回调已触发
        assertThat(mergeEvents).contains(hash);

        // 6. 秒传：再次 check 直接 finished
        CheckResponse instant = check(hash);
        assertThat(instant.finished()).isTrue();
        assertThat(instant.fileName()).isEqualTo(fileName);
        assertThat(instant.size()).isEqualTo((long) total.length);
        assertThat(instant.url()).isEqualTo("/api/files/" + hash + "/download");

        // 7. 秒传命中后再传分片 → 409
        assertThat(uploadChunkRaw(hash, 0, 3, c0).getStatusCode().value()).isEqualTo(409);

        // 8. 全量下载：内容逐字节一致
        ResponseEntity<byte[]> full = rest.getForEntity("/api/files/" + hash + "/download", byte[].class);
        assertThat(full.getStatusCode().value()).isEqualTo(200);
        assertThat(full.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(full.getHeaders().getETag()).isEqualTo("\"" + hash + "\"");
        assertThat(full.getBody()).containsExactly(total);

        // 9. Range 区间下载（起点取第 2 片开头，跨片边界语义）
        ResponseEntity<byte[]> range = downloadWithRange(hash, "bytes=" + NON_LAST + "-" + (NON_LAST + 4));
        assertThat(range.getStatusCode().value()).isEqualTo(206);
        assertThat(range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + NON_LAST + "-" + (NON_LAST + 4) + "/" + total.length);
        assertThat(range.getBody()).containsExactly(fill('b', 5));

        // 10. 后缀 Range（-10 → 最后 10 字节）
        ResponseEntity<byte[]> suffix = downloadWithRange(hash, "bytes=-10");
        assertThat(suffix.getStatusCode().value()).isEqualTo(206);
        assertThat(suffix.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + (total.length - 10) + "-" + (total.length - 1) + "/" + total.length);
        assertThat(suffix.getBody()).containsExactly(fill('c', 10));

        // 11. 起点越界 → 416
        ResponseEntity<byte[]> outOfRange = downloadWithRange(hash, "bytes=" + total.length + "-" + (total.length + 10));
        assertThat(outOfRange.getStatusCode().value()).isEqualTo(416);
        assertThat(outOfRange.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */" + total.length);

        // 12. If-Range 不匹配 → 退回 200 全量
        HttpHeaders ifRangeHeaders = new HttpHeaders();
        ifRangeHeaders.set(HttpHeaders.RANGE, "bytes=0-4");
        ifRangeHeaders.set(HttpHeaders.IF_RANGE, "\"stale-etag\"");
        ResponseEntity<byte[]> stale = rest.exchange("/api/files/" + hash + "/download", HttpMethod.GET,
                new HttpEntity<>(ifRangeHeaders), byte[].class);
        assertThat(stale.getStatusCode().value()).isEqualTo(200);
        assertThat(stale.getBody()).containsExactly(total);

        // 13. 删除 → 下载 404、check 回到未传状态、删除事件触发
        ResponseEntity<Map<String, Object>> deleted = rest.exchange("/api/files/" + hash, HttpMethod.DELETE, null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(deleted.getBody()).containsEntry("deleted", true);
        assertThat(rest.getForEntity("/api/files/" + hash + "/download", byte[].class).getStatusCode().value())
                .isEqualTo(404);
        assertThat(check(hash).finished()).isFalse();
        assertThat(deletedEvents).contains(hash);
    }

    /* ---------------- 错误语义 ---------------- */

    @Test
    @Order(2)
    void merge_withMissingChunks_conflict409() {
        String hash = md5Hex("missing-chunks-case".getBytes(StandardCharsets.UTF_8));
        assertThat(uploadChunkRaw(hash, 0, 3, fill('x', NON_LAST)).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> merged = rest.postForEntity("/api/upload/merge",
                json(Map.of("fileHash", hash, "fileName", "缺片.bin",
                        "totalSize", 2L * NON_LAST + 64, "totalChunks", 3, "chunkSize", NON_LAST)),
                Map.class);
        assertThat(merged.getStatusCode().value()).isEqualTo(409);
        assertThat((String) merged.getBody().get("message")).contains("分片缺失").contains("1, 2");

        // 取消上传清理分片
        ResponseEntity<Map<String, Object>> cleaned = rest.exchange("/api/upload/" + hash, HttpMethod.DELETE, null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(cleaned.getBody()).containsEntry("cleaned", true);
        assertThat(check(hash).uploadedChunks()).isEmpty();
    }

    @Test
    @Order(3)
    void merge_withWrongTotalSize_conflict409() {
        String hash = md5Hex("wrong-total-size-case".getBytes(StandardCharsets.UTF_8));
        assertThat(uploadChunkRaw(hash, 0, 2, fill('x', NON_LAST)).getStatusCode().value()).isEqualTo(200);
        assertThat(uploadChunkRaw(hash, 1, 2, fill('y', 10)).getStatusCode().value()).isEqualTo(200);

        long actualSum = (long) NON_LAST + 10;
        long declared = actualSum + 15;
        ResponseEntity<Map> merged = rest.postForEntity("/api/upload/merge",
                json(Map.of("fileHash", hash, "fileName", "对不上.bin",
                        "totalSize", declared, "totalChunks", 2, "chunkSize", NON_LAST)),
                Map.class);
        assertThat(merged.getStatusCode().value()).isEqualTo(409);
        assertThat((String) merged.getBody().get("message"))
                .contains("分片总大小").contains(String.valueOf(actualSum)).contains(String.valueOf(declared));
    }

    @Test
    @Order(4)
    void invalidHash_badRequest400() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/upload/check",
                json(Map.of("fileHash", "含非法字符!", "fileName", "x", "totalSize", 1, "totalChunks", 1, "chunkSize", 1)),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("fileHash 非法");
    }

    /* ---------------- 工具（子类复用） ---------------- */

    protected CheckResponse check(String hash) {
        ResponseEntity<CheckResponse> resp = rest.postForEntity("/api/upload/check",
                json(Map.of("fileHash", hash, "fileName", "whatever.bin",
                        "totalSize", 350, "totalChunks", 3, "chunkSize", 128)),
                CheckResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody();
    }

    protected HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        return new HttpEntity<>(body);
    }

    protected ChunkSavedResponse uploadChunk(String hash, int index, int total, byte[] data) {
        ResponseEntity<ChunkSavedResponse> resp = uploadChunkRaw(hash, index, total, data);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody();
    }

    protected ResponseEntity<ChunkSavedResponse> uploadChunkRaw(String hash, int index, int total, byte[] data) {
        HttpHeaders headers = new HttpHeaders();
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
        return rest.postForEntity("/api/upload/chunk", new HttpEntity<>(body, headers), ChunkSavedResponse.class);
    }

    /** 同 uploadChunkRaw，但响应体按 Map 读取——用于断言 4xx 错误响应的消息内容 */
    protected ResponseEntity<Map> uploadChunkRawAsMap(String hash, int index, int total, byte[] data) {
        HttpHeaders headers = new HttpHeaders();
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
        return rest.exchange("/api/upload/chunk", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    protected ResponseEntity<byte[]> downloadWithRange(String hash, String range) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, range);
        return rest.exchange("/api/files/" + hash + "/download", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
    }

    protected static byte[] fill(char c, int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) c);
        return data;
    }

    protected static byte[] concat(byte[]... parts) {
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

    protected static String md5Hex(byte[] data) {
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
