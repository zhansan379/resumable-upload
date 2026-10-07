package com.example.upload.store.s3;

/** 预签名分片上传地址：chunkIndex 为协议编号（0 起），url 由调用方直接 PUT 分片内容 */
public record PresignedPart(int chunkIndex, String url, long expiresAtEpochMillis) {
}
