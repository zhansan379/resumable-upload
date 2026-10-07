package com.example.upload.dto;

/**
 * 预签名直传初始化请求（type=s3 且 s3.direct-upload=true 时可用）：
 * 前端先算整文件 MD5，再调 init 创建/复用 multipart 会话。
 */
public record DirectInitRequest(
        String fileHash,
        String fileName,
        long totalSize,
        int totalChunks) {
}
