package com.example.upload.controller;

import com.example.upload.dto.CheckRequest;
import com.example.upload.dto.CheckResponse;
import com.example.upload.dto.ChunkSavedResponse;
import com.example.upload.dto.FileItem;
import com.example.upload.dto.MergeRequest;
import com.example.upload.dto.MergeResponse;
import com.example.upload.exception.BusinessException;
import com.example.upload.service.UploadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * 上传协议端点（吸收 resumable.js 参数协议 + fine-uploader 显式合并端点 + filepond revert 思想）：
 * - POST /api/upload/check   秒传 + 断点续传探测（一次返回已传分片列表，避免逐片 GET）
 * - POST /api/upload/chunk   上传单个分片（multipart）
 * - POST /api/upload/merge   全片完成后显式合并
 * - GET  /api/files          已上传文件列表
 * - GET  /api/files/{hash}/download  下载
 * - DELETE /api/files/{hash} 删除文件
 * - DELETE /api/upload/{hash} 取消上传并清理分片
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload/check")
    public CheckResponse check(@RequestBody CheckRequest request) {
        return uploadService.check(request);
    }

    @PostMapping("/upload/chunk")
    public ChunkSavedResponse uploadChunk(
            @RequestParam("fileHash") String fileHash,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("totalChunks") Integer totalChunks,
            @RequestParam(value = "chunkSize", required = false) Long chunkSize,
            @RequestParam(value = "totalSize", required = false) Long totalSize,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestPart("chunk") MultipartFile chunk) {
        return uploadService.saveChunk(fileHash, chunkIndex, totalChunks, chunk);
    }

    @PostMapping("/upload/merge")
    public MergeResponse merge(@RequestBody MergeRequest request) {
        return uploadService.merge(request);
    }

    @GetMapping("/files")
    public List<FileItem> listFiles() {
        return uploadService.listFiles();
    }

    /**
     * 下载：支持 HTTP Range 断点续传（RFC 7233）。
     * - 无 Range 头 → 200 全量，带 Accept-Ranges: bytes 声明续传能力；
     * - 有 Range → 206 Partial Content + Content-Range: bytes start-end/total；
     * - ETag 取 fileHash（内容寻址，天然不变），配合 If-Range：不匹配则退回 200 全量，避免拼接出损坏文件；
     * - start 超界返回 416 + Content-Range: bytes *&#47;total；非法/多区间 Range 按 RFC 忽略，返回 200 全量。
     */
    @GetMapping("/files/{fileHash}/download")
    public ResponseEntity<Resource> download(@PathVariable String fileHash,
                                             @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                             @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {
        UploadService.DownloadInfo info = uploadService.downloadInfo(fileHash);
        String etag = "\"" + fileHash + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag(etag);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(info.fileName(), StandardCharsets.UTF_8)
                .build());

        boolean ifRangeSatisfied = ifRange == null || ifRange.equals(etag);
        List<HttpRange> ranges = List.of();
        if (rangeHeader != null && ifRangeSatisfied) {
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException ignored) {
                // 非法 Range 头按 RFC 7233 忽略，返回 200 全量
            }
        }
        if (ranges.size() != 1) {
            // 无 Range / 非法 / 多区间（multipart/byteranges 未支持）→ 200 全量
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(info.size())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(info.path()));
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(info.size());
        long end = range.getRangeEnd(info.size());
        if (start >= info.size()) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + info.size());
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .headers(headers)
                    .build();
        }

        long length = end - start + 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + info.size());
        try {
            InputStream regionStream = openRegion(info.path(), start, length);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(headers)
                    .contentLength(length)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(regionStream));
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件区间失败：" + e.getMessage());
        }
    }

    /** 打开文件并定位到区间起点，流被限定为最多读取 length 字节（读完即 EOF） */
    private InputStream openRegion(Path path, long start, long length) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        channel.position(start);
        return new InputStream() {
            private long remaining = length;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = channel.read(ByteBuffer.allocate(1));
                if (b < 0) {
                    remaining = 0;
                    return -1;
                }
                remaining--;
                return b & 0xFF;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = channel.read(ByteBuffer.wrap(buf, off, (int) Math.min(len, remaining)));
                if (n < 0) {
                    remaining = 0;
                    return -1;
                }
                remaining -= n;
                return n;
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    @DeleteMapping("/files/{fileHash}")
    public Map<String, Object> deleteFile(@PathVariable String fileHash) {
        return Map.of("deleted", uploadService.deleteFile(fileHash));
    }

    @DeleteMapping("/upload/{fileHash}")
    public Map<String, Object> cancelUpload(@PathVariable String fileHash) {
        return Map.of("cleaned", uploadService.cleanChunks(fileHash));
    }
}
