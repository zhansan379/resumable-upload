package com.example.upload.controller;

import com.example.upload.dto.CheckRequest;
import com.example.upload.dto.CheckResponse;
import com.example.upload.dto.ChunkSavedResponse;
import com.example.upload.dto.FileItem;
import com.example.upload.dto.MergeRequest;
import com.example.upload.dto.MergeResponse;
import com.example.upload.service.UploadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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

    @GetMapping("/files/{fileHash}/download")
    public ResponseEntity<Resource> download(@PathVariable String fileHash) {
        UploadService.DownloadInfo info = uploadService.downloadInfo(fileHash);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(info.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(info.size())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(info.path()));
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
