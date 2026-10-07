package com.example.upload.dto;

import java.util.List;

/** 批量获取预签名分片上传地址请求 */
public record DirectPartUrlsRequest(
        String fileHash,
        String uploadId,
        List<Integer> chunkIndexes) {
}
