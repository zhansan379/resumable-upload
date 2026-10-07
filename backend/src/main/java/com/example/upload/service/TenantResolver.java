package com.example.upload.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 租户来源 SPI：组件不规定租户从哪来，宿主的租户体系是什么样就接什么样。
 * <p>
 * 默认实现 {@link HeaderTenantResolver} 从可配置请求头读取（零依赖开箱即用）；
 * 宿主已有租户体系（JWT claims / Spring Security 上下文 / ThreadLocal /
 * MyBatis-Plus TenantLineHandler 等）时，注册自己的 {@code TenantResolver} Bean 即可覆盖默认实现。
 * <p>
 * 实现约定：返回 null 表示本次请求无租户（未启用租户语义）；
 * 租户字符集校验由协议层统一执行（路径注入防护不可绕过），实现只负责"取"不负责"验"；
 * 取不到租户但宿主业务要求必须有（如启用了租户头）时，实现自行抛业务异常（400/401）。
 */
public interface TenantResolver {

    /**
     * @return 租户标识；null = 无租户（组件按未启用租户处理）
     */
    String resolve(HttpServletRequest request);
}
