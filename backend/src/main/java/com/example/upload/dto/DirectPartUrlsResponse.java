package com.example.upload.dto;

import java.util.List;

/** 批量预签名分片地址响应 */
public record DirectPartUrlsResponse(List<DirectPartUrl> urls) {
}
