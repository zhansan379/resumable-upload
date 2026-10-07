package com.example.upload.controller;

import com.example.upload.dto.DirectInitRequest;
import com.example.upload.dto.DirectInitResponse;
import com.example.upload.dto.DirectPartUrlsRequest;
import com.example.upload.dto.DirectPartUrlsResponse;
import com.example.upload.service.DirectUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预签名直传端点（type=s3 且 s3.direct-upload=true 时装配，挂载前缀跟随 api-prefix）：
 * - POST {prefix}/upload/direct/init       秒传判定 + 创建/复用 multipart 会话
 * - POST {prefix}/upload/direct/part-urls  批量签发分片直传地址（客户端 PUT 到对象存储）
 * 合并与取消复用既有端点（/upload/merge、DELETE /upload/{hash}）。
 * 安全：这两个端点等价于"授权写入对应对象"，必须纳入宿主鉴权拦截。
 */
@RestController
@RequestMapping("${app.upload.api-prefix:/api}")
@Conditional(com.example.upload.config.DirectUploadEnabled.class)
public class DirectUploadController {

    private final DirectUploadService directUploadService;

    public DirectUploadController(DirectUploadService directUploadService) {
        this.directUploadService = directUploadService;
    }

    @PostMapping("/upload/direct/init")
    public DirectInitResponse init(@RequestBody DirectInitRequest request, HttpServletRequest httpRequest) {
        return directUploadService.init(httpRequest, request);
    }

    @PostMapping("/upload/direct/part-urls")
    public DirectPartUrlsResponse partUrls(@RequestBody DirectPartUrlsRequest request, HttpServletRequest httpRequest) {
        return directUploadService.partUrls(httpRequest, request);
    }
}
