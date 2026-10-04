package com.example.upload.dto;

/** 合并响应 */
public record MergeResponse(
        String fileHash,
        String fileName,
        String url,
        long size,
        boolean verified) {
}
