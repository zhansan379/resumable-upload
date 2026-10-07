package com.example.upload.service;

import com.example.upload.config.UploadProperties;
import com.example.upload.dto.DirectInitRequest;
import com.example.upload.dto.DirectInitResponse;
import com.example.upload.dto.DirectPartUrl;
import com.example.upload.dto.DirectPartUrlsRequest;
import com.example.upload.dto.DirectPartUrlsResponse;
import com.example.upload.exception.BusinessException;
import com.example.upload.store.FileRecord;
import com.example.upload.store.spi.ChunkStorage;
import com.example.upload.store.spi.FileStorage;
import com.example.upload.store.spi.MetadataStore;
import com.example.upload.store.s3.S3UploadStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 预签名直传服务（type=s3 且 s3.direct-upload=true 时装配）：
 * 浏览器直传对象存储，服务端只做会话管理与 URL 签发，零带宽转发。
 * 流程：init（秒传判定 + 建会话）→ part-urls（批量签发，可按批拉取）→ 客户端 PUT 到对象存储
 * → 复用既有 /upload/merge（服务端 ListParts 取全部 ETag 后 complete，客户端无需回传 ETag）
 * → 取消复用 DELETE /upload/{hash}（中止会话）。
 * 注意：init/part-urls 必须纳入宿主鉴权（签发即授权写入对应对象）。
 */
@Service
@Conditional(com.example.upload.config.DirectUploadEnabled.class)
public class DirectUploadService {

    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{8,64}$");

    private final S3UploadStorage s3Storage;
    private final MetadataStore indexStore;
    private final FileStorage fileStorage;
    private final ChunkStorage chunkStorage;
    private final UploadProperties props;
    private final UploadService uploadService;
    private final long maxTotalBytes;

    public DirectUploadService(S3UploadStorage s3Storage, MetadataStore indexStore, FileStorage fileStorage,
                               ChunkStorage chunkStorage, UploadProperties props, UploadService uploadService) {
        this.s3Storage = s3Storage;
        this.indexStore = indexStore;
        this.fileStorage = fileStorage;
        this.chunkStorage = chunkStorage;
        this.props = props;
        this.uploadService = uploadService;
        this.maxTotalBytes = DataSize.parse(props.getMaxTotalSize()).toBytes();
    }

    public DirectInitResponse init(HttpServletRequest request, DirectInitRequest req) {
        String tenant = uploadService.currentTenant(request);
        String hash = validateHash(req.fileHash());
        if (req.totalChunks() < 1 || req.totalChunks() > chunkStorage.maxChunks()) {
            throw BusinessException.badRequest("totalChunks 非法（1 ~ " + chunkStorage.maxChunks() + "）");
        }
        if (req.totalSize() <= 0 || req.totalSize() > maxTotalBytes) {
            throw BusinessException.badRequest("totalSize 非法或超过服务端上限 " + props.getMaxTotalSize());
        }
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw BusinessException.badRequest("fileName 不能为空");
        }

        // 秒传：同租户内 hash 命中且产物存在
        FileRecord rec = indexStore.get(tenant, hash);
        if (rec != null && fileStorage.exists(rec.getStoredPath())) {
            return new DirectInitResponse(true, null, List.of(), chunkStorage.minNonLastPartSize());
        }

        List<Integer> uploaded;
        try {
            uploaded = chunkStorage.list(scopedId(tenant, hash)).stream()
                    .map(ci -> ci.index()).toList();
        } catch (java.io.IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取分片状态失败：" + e.getMessage());
        }
        String uploadId = s3Storage.initSession(scopedId(tenant, hash));
        return new DirectInitResponse(false, uploadId, uploaded, chunkStorage.minNonLastPartSize());
    }

    public DirectPartUrlsResponse partUrls(HttpServletRequest request, DirectPartUrlsRequest req) {
        String tenant = uploadService.currentTenant(request);
        String hash = validateHash(req.fileHash());
        if (req.chunkIndexes() == null || req.chunkIndexes().isEmpty()) {
            throw BusinessException.badRequest("chunkIndexes 不能为空");
        }
        try {
            List<DirectPartUrl> urls = s3Storage
                    .presignParts(scopedId(tenant, hash), req.uploadId(), req.chunkIndexes(),
                            props.getStorage().getS3().getDirectUrlTtl())
                    .stream()
                    .map(p -> new DirectPartUrl(p.chunkIndex(), p.url(), p.expiresAtEpochMillis()))
                    .toList();
            return new DirectPartUrlsResponse(urls);
        } catch (IllegalArgumentException e) {
            throw BusinessException.conflict(e.getMessage());
        }
    }

    private String validateHash(String hash) {
        if (hash == null || !HASH_PATTERN.matcher(hash).matches()) {
            throw BusinessException.badRequest("fileHash 非法（应为 8-64 位十六进制）");
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    private String scopedId(String tenant, String hash) {
        return tenant == null ? hash : tenant + "/" + hash;
    }
}
