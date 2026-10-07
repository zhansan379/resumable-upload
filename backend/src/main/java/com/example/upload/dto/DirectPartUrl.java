package com.example.upload.dto;

/** 单个预签名分片地址：PUT 该 url 的 body 即分片内容（成功响应的 ETag 无需回传，合并时服务端 ListParts 自取） */
public record DirectPartUrl(int chunkIndex, String url, long expiresAtEpochMillis) {
}
