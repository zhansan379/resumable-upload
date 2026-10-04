package com.example.upload.dto;

/** 已上传文件列表项 */
public record FileItem(
        String fileHash,
        String fileName,
        long size,
        String uploadTime,
        boolean verified,
        String downloadUrl) {
}
