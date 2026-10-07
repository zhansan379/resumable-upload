package com.example.upload.store.s3;

import com.example.upload.config.UploadProperties;
import com.example.upload.store.spi.ChunkInfo;
import com.example.upload.store.spi.ChunkStorage;
import com.example.upload.store.spi.ContentHandle;
import com.example.upload.store.spi.FileStorage;
import com.example.upload.store.spi.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3 兼容对象存储后端（MinIO / RustFS / AWS S3 / OSS / COS / OBS 等一切 S3 端点）。
 * 同一个类实现两个 SPI：分片与合并产物共享 multipart 会话状态，拆开反而要跨类同步。
 * <p>
 * 语义映射（对应 docs/03「存储分层」）：
 * <ul>
 *   <li>分片保存 = uploadPart（编号 0 起 → S3 partNumber 1 起；会话按需懒创建）；
 *       分片不再落本地盘，直接进对象存储的进行中 multipart 会话；</li>
 *   <li>分片枚举 = ListParts（分页拉全）；取消 = abortMultipartUpload；</li>
 *   <li>合并 = completeMultipartUpload（按 partNumber 升序提交全部 ETag）；</li>
 *   <li>秒传/存在性 = HeadObject（key = {keyPrefix}/{fileHash}）；</li>
 *   <li>下载/Range = GetObject + Range 请求头；</li>
 *   <li>孤儿清理 = ListMultipartUploads + abort 过期会话。</li>
 * </ul>
 * 会话状态（uploadId）不落本地：每次按 key 从 ListMultipartUploads 反查，
 * 与"存储端为事实源"的既有设计一致，也天然支持多实例。硬约束：
 * 非末片 ≥ 5MiB（协议层前置拒绝）、单片上限 10000（能力声明由协议层收紧）。
 */
public class S3UploadStorage implements ChunkStorage, FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3UploadStorage.class);

    /** S3 multipart 硬约束：除最后一个分片外，每片至少 5MiB */
    static final long MIN_NON_LAST_PART_SIZE = 5L * 1024 * 1024;
    /** S3 multipart 硬约束：单个对象最多 10000 片 */
    static final int MAX_PARTS = 10_000;

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    /** 首个分片到达时懒创建会话，per-hash 锁防止并发重复创建 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public S3UploadStorage(S3Client s3, UploadProperties props) {
        this.s3 = s3;
        UploadProperties.S3 cfg = props.getStorage().getS3();
        this.bucket = cfg.getBucket();
        this.keyPrefix = cfg.getKeyPrefix() == null ? "" : cfg.getKeyPrefix().replaceAll("^/+|/+$", "");
    }

    /** 启动时确认桶可用（缺失则创建），把配置错误挡在启动阶段 */
    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.info("存储桶不存在，创建: {}", bucket);
                s3.createBucket(b -> b.bucket(bucket));
            } else {
                throw new IllegalStateException("访问存储桶失败（检查 endpoint/凭证/权限）: " + bucket, e);
            }
        }
    }

    String key(String fileHash) {
        return keyPrefix.isEmpty() ? fileHash : keyPrefix + "/" + fileHash;
    }

    /* ---------------- ChunkStorage ---------------- */

    @Override
    public long save(String fileHash, int chunkIndex, int totalChunks, long contentLength, InputStream in)
            throws IOException {
        String k = key(fileHash);
        String uploadId = getOrCreateUploadId(fileHash);
        try (in) {
            s3.uploadPart(UploadPartRequest.builder()
                            .bucket(bucket).key(k).uploadId(uploadId)
                            .partNumber(chunkIndex + 1) // 协议 0 起 → S3 1 起
                            .build(),
                    RequestBody.fromInputStream(in, contentLength));
            return contentLength;
        } catch (SdkException e) {
            throw new IOException("分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChunkInfo> list(String fileHash) throws IOException {
        String k = key(fileHash);
        String uploadId = findUploadId(k);
        if (uploadId == null) {
            return List.of();
        }
        try {
            return listAllParts(k, uploadId).stream()
                    .map(p -> new ChunkInfo(p.partNumber() - 1, p.size()))
                    .sorted(Comparator.comparingInt(ChunkInfo::index))
                    .toList();
        } catch (NoSuchUploadException e) {
            // 会话已被中止/完成：等价于"没有分片"
            return List.of();
        } catch (SdkException e) {
            throw new IOException("读取分片状态失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteAll(String fileHash) {
        String k = key(fileHash);
        String uploadId = findUploadId(k);
        if (uploadId != null) {
            log.info("中止分片上传会话: key={}", k);
            abortQuietly(k, uploadId);
        }
    }

    @Override
    public int deleteOrphansOlderThan(Instant deadline) {
        int cleaned = 0;
        String keyMarker = null;
        String idMarker = null;
        while (true) {
            ListMultipartUploadsResponse resp = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                    .bucket(bucket)
                    .prefix(keyPrefix.isEmpty() ? null : keyPrefix + "/")
                    .keyMarker(keyMarker).uploadIdMarker(idMarker)
                    .build());
            for (MultipartUpload upload : resp.uploads()) {
                if (upload.initiated() != null && upload.initiated().isBefore(deadline)) {
                    log.info("清理过期分片上传会话: key={} uploadId={}", upload.key(), upload.uploadId());
                    abortQuietly(upload.key(), upload.uploadId());
                    cleaned++;
                }
            }
            if (!resp.isTruncated()) {
                break;
            }
            keyMarker = resp.nextKeyMarker();
            idMarker = resp.nextUploadIdMarker();
        }
        return cleaned;
    }

    @Override
    public long minNonLastPartSize() {
        return MIN_NON_LAST_PART_SIZE;
    }

    @Override
    public int maxChunks() {
        return MAX_PARTS;
    }

    /* ---------------- FileStorage ---------------- */

    @Override
    public StoredObject completeMerge(String fileHash, String safeFileName, long totalSize, int totalChunks)
            throws IOException {
        String k = key(fileHash);

        // 恢复路径：最终对象已存在 = 上次合并成功但索引写入失败；大小一致复用，不一致删除重做
        try {
            long existing = headSize(k);
            if (existing == totalSize) {
                log.warn("目标对象已存在且大小一致，跳过合并直接入索引: {}", k);
                abortSessionsQuietly(k);
                return new StoredObject(k, existing);
            }
            log.warn("目标对象已存在但大小不一致（{} 字节），删除后重新合并: {}", existing, k);
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k).build());
        } catch (FileNotFoundException ignored) {
            // 正常路径：尚无最终对象
        }

        String uploadId = findUploadId(k);
        if (uploadId == null) {
            throw new FileNotFoundException("没有进行中的分片上传会话: " + k);
        }
        List<Part> parts = listAllParts(k, uploadId);
        if (parts.size() != totalChunks) {
            // 不提交残缺会话：保持会话与分片不动，客户端补片后重试
            throw new IOException("分片数量不完整: 已有 " + parts.size() + " 片，期望 " + totalChunks + " 片");
        }
        long sum = parts.stream().mapToLong(Part::size).sum();
        if (sum != totalSize) {
            throw new IOException("分片总大小 " + sum + " 与文件大小 " + totalSize + " 不一致");
        }
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(Part::partNumber))
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                .toList();
        try {
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(k).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
        } catch (SdkException e) {
            throw new IOException("完成合并失败: " + e.getMessage(), e);
        }
        // 清理并发窗口内可能残留的重复会话
        abortSessionsQuietly(k);
        return new StoredObject(k, headSize(k));
    }

    @Override
    public StoredObject stat(String locator) throws IOException {
        try {
            return new StoredObject(locator, headSize(locator));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("对象不存在: " + locator);
        }
    }

    @Override
    public ContentHandle open(String locator) throws IOException {
        long size;
        try {
            size = headSize(locator);
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("对象不存在: " + locator);
        }
        return new S3ContentHandle(s3, bucket, locator, size);
    }

    @Override
    public void delete(String locator) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(locator).build());
            abortSessionsQuietly(locator);
        } catch (SdkException e) {
            log.warn("删除对象失败: {}", locator, e);
        }
    }

    @Override
    public long usableBytes() {
        // 对象存储无本地容量概念：返回 0，协议层据此跳过磁盘水位前置检查
        return 0;
    }

    /* ---------------- 内部：multipart 会话管理 ---------------- */

    private long headSize(String k) throws IOException {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(k).build()).contentLength();
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("对象不存在: " + k);
        } catch (SdkException e) {
            throw new IOException("读取对象元数据失败: " + e.getMessage(), e);
        }
    }

    private String getOrCreateUploadId(String fileHash) {
        String k = key(fileHash);
        synchronized (sessionLocks.computeIfAbsent(fileHash, x -> new Object())) {
            String existing = findUploadId(k);
            if (existing != null) {
                return existing;
            }
            String created = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(k).build()).uploadId();
            // 并发窗口内他人已建会话：丢弃自己刚建的空会话，复用已有（其上可能已有分片）
            String again = findUploadId(k);
            if (again != null && !again.equals(created)) {
                abortQuietly(k, created);
                return again;
            }
            return created;
        }
    }

    private String findUploadId(String k) {
        String keyMarker = null;
        String idMarker = null;
        while (true) {
            ListMultipartUploadsResponse resp = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix(k)
                    .keyMarker(keyMarker).uploadIdMarker(idMarker)
                    .build());
            Optional<MultipartUpload> hit = resp.uploads().stream()
                    .filter(u -> u.key().equals(k))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get().uploadId();
            }
            if (!resp.isTruncated()) {
                return null;
            }
            keyMarker = resp.nextKeyMarker();
            idMarker = resp.nextUploadIdMarker();
        }
    }

    private List<Part> listAllParts(String k, String uploadId) {
        List<Part> parts = new ArrayList<>();
        Integer marker = null;
        while (true) {
            ListPartsResponse resp = s3.listParts(ListPartsRequest.builder()
                    .bucket(bucket).key(k).uploadId(uploadId)
                    .partNumberMarker(marker)
                    .build());
            parts.addAll(resp.parts());
            if (!resp.isTruncated()) {
                return parts;
            }
            marker = resp.nextPartNumberMarker();
        }
    }

    private void abortSessionsQuietly(String k) {
        String keyMarker = null;
        String idMarker = null;
        while (true) {
            ListMultipartUploadsResponse resp = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix(k)
                    .keyMarker(keyMarker).uploadIdMarker(idMarker)
                    .build());
            for (MultipartUpload upload : resp.uploads()) {
                if (upload.key().equals(k)) {
                    abortQuietly(k, upload.uploadId());
                }
            }
            if (!resp.isTruncated()) {
                return;
            }
            keyMarker = resp.nextKeyMarker();
            idMarker = resp.nextUploadIdMarker();
        }
    }

    private void abortQuietly(String k, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(k).uploadId(uploadId).build());
        } catch (NoSuchUploadException ignored) {
            // 已经不存在，目标状态已达成
        } catch (SdkException e) {
            log.warn("中止分片上传会话失败: key={} uploadId={}", k, uploadId, e);
        }
    }

    /**
     * 对象内容只读句柄：每次读取独立发 GetObject（Range 用请求头），
     * 流关闭即释放 HTTP 连接，句柄本身无共享资源（close 默认空实现足够）。
     */
    private record S3ContentHandle(S3Client s3, String bucket, String key, long size) implements ContentHandle {

        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream readFully() {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        }

        @Override
        public InputStream readRange(long start, long length) {
            long end = Math.min(start + length, size) - 1;
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key)
                    .range("bytes=" + start + "-" + end)
                    .build());
        }
    }
}
