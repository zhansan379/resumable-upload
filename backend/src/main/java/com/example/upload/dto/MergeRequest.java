package com.example.upload.dto;

/** 合并请求（全部分片上传完成后由前端发起） */
public record MergeRequest(
        String fileHash,
        String fileName,
        long totalSize,
        int totalChunks,
        long chunkSize) {
}
