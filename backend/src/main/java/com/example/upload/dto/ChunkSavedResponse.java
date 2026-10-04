package com.example.upload.dto;

/** 单个分片保存成功响应 */
public record ChunkSavedResponse(int chunkIndex, long size) {
}
