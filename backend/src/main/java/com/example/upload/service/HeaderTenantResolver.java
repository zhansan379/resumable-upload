package com.example.upload.service;

import com.example.upload.config.UploadProperties;
import com.example.upload.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 默认租户实现：从 {@code app.upload.tenant.header} 指定的请求头读取。
 * 头名未配置（默认空串）→ 一律返回 null（组件按未启用租户处理）；
 * 头名已配置但请求缺失该头 → 400（显式失败，不静默归入默认租户）。
 * 宿主已有租户体系时，注册自己的 TenantResolver Bean 覆盖本实现。
 */
public class HeaderTenantResolver implements TenantResolver {

    private final String headerName;

    public HeaderTenantResolver(UploadProperties props) {
        this.headerName = props.getTenant().getHeader();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (headerName == null || headerName.isBlank()) {
            return null;
        }
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("缺少租户请求头 " + headerName);
        }
        return value;
    }
}
