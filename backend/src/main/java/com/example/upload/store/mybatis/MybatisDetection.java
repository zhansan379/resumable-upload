package com.example.upload.store.mybatis;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * 元数据实现探测的公共判断。
 * <p>
 * 关键语义：类路径有 MyBatis 系 starter ≠ 能用——auto 模式还要求宿主**真的配置了数据源**
 * （spring.datasource.url）。否则 MyBatis 自动装配会因缺 DataSource 拖垮整个应用启动
 * （"零配置开箱即跑"是本组件的承诺，MyBatis 依赖在类路径上时也不能破坏它）。
 * 显式指定 mybatis/mybatis-plus 但未配置数据源 → 启动失败并给出可执行的修复建议。
 */
final class MybatisDetection {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MybatisDetection.class);

    /** MyBatis-Plus 宿主特征类 */
    static final String MYBATIS_PLUS_CLASS = "com.baomidou.mybatisplus.core.mapper.BaseMapper";
    /** MyBatis-Spring 集成特征类（starter 自动装配存在的前提） */
    static final String MYBATIS_SPRING = "org.mybatis.spring.SqlSessionFactoryBean";

    static final String PROP = "app.upload.metadata.type";
    static final String AUTO = "auto";
    static final String JSON = "json";
    static final String MYBATIS = "mybatis";
    static final String MYBATIS_PLUS = "mybatis-plus";

    private MybatisDetection() {
    }

    static String typeOf(ConditionContext ctx) {
        return ctx.getEnvironment().getProperty(PROP, AUTO);
    }

    static boolean mybatisPlusPresent(ConditionContext ctx) {
        return ClassUtils.isPresent(MYBATIS_PLUS_CLASS, ctx.getClassLoader());
    }

    static boolean mybatisSpringPresent(ConditionContext ctx) {
        return ClassUtils.isPresent(MYBATIS_SPRING, ctx.getClassLoader());
    }

    /** 数据源是否已配置：MyBatis 系自动装配的前置条件（仅看 spring.datasource.url，够用且可靠） */
    static boolean datasourceConfigured(ConditionContext ctx) {
        return StringUtils.hasText(ctx.getEnvironment().getProperty("spring.datasource.url"));
    }

    /** auto 模式下 MyBatis-Plus 运行时是否真的可用 */
    static boolean mpRuntimeAvailable(ConditionContext ctx) {
        return mybatisPlusPresent(ctx) && datasourceConfigured(ctx);
    }

    /** auto 模式下纯 MyBatis 运行时是否真的可用 */
    static boolean mybatisRuntimeAvailable(ConditionContext ctx) {
        return mybatisSpringPresent(ctx) && datasourceConfigured(ctx);
    }

    /** auto 回落 JSON 时给出可见告警：类路径有 MyBatis 但数据源未配置 */
    static void warnJsonFallback(ConditionContext ctx) {
        if (mybatisPlusPresent(ctx) || mybatisSpringPresent(ctx)) {
            log.warn("检测到类路径存在 MyBatis/MyBatis-Plus，但未配置 spring.datasource.url，"
                    + "元数据回落为 JSON 文件（单实例）。多实例部署请配置数据源"
                    + "（app.upload.metadata.type 保持 auto 或显式指定 mybatis/mybatis-plus）。");
        }
    }

    static IllegalStateException missingDependency(String type, String starter) {
        return new IllegalStateException("app.upload.metadata.type=" + type + " 需要引入依赖：" + starter);
    }

    static IllegalStateException missingDatasource(String type) {
        return new IllegalStateException("app.upload.metadata.type=" + type
                + " 需要配置 spring.datasource.*（宿主数据源）；不使用数据库请改回 metadata.type=json 或 auto");
    }
}
