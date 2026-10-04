package com.example.upload.dto;

import java.util.List;

/**
 * 分片状态探测响应：
 * finished = true 时为"秒传"（服务端已存在相同 hash 的文件）；
 * 否则 uploadedChunks 为服务端磁盘上已存在的分片编号（0 起），前端据此跳过已传分片实现断点续传。
 */
public record CheckResponse(
        boolean finished,
        List<Integer> uploadedChunks,
        String url,
        String fileName,
        Long size,
        Boolean verified) {
}
