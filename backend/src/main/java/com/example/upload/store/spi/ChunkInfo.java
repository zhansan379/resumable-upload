package com.example.upload.store.spi;

/**
 * 单个已存在分片的信息：编号（0 起）与实际落盘字节数。
 * 合并前的"分片齐全性 + 总字节一致性"校验只依赖这两个值，
 * 因此无论分片在本地磁盘还是对象存储（如 S3 ListParts）都能提供。
 */
public record ChunkInfo(int index, long size) {
}
