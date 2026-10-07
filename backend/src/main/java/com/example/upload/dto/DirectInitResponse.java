package com.example.upload.dto;

import java.util.List;

/**
 * 预签名直传初始化响应：
 * finished=true 为秒传命中（后续直接走 /files/{hash}/download）；
 * 否则 uploadId 供 part-urls 与（可选）状态查询使用，
 * uploadedChunks 为已直传成功的分片（断点续传），minNonLastPartSize 为非末片分片最小字节数（S3 = 5MiB）。
 */
public record DirectInitResponse(
        boolean finished,
        String uploadId,
        List<Integer> uploadedChunks,
        long minNonLastPartSize) {
}
