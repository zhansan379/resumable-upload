package com.example.upload.dto;

/** 分片状态探测 / 秒传检查请求（前端 spark-md5 计算整文件 hash 后调用） */
public record CheckRequest(
        String fileHash,
        String fileName,
        long totalSize,
        int totalChunks,
        long chunkSize) {
}
