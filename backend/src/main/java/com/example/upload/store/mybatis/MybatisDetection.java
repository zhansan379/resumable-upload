package com.example.upload.store.mybatis;

import org.springframework.util.ClassUtils;

/**
 * 元数据实现探测的公共判断。类路径优先级：MyBatis-Plus > MyBatis（auto 模式）。
 */
final class MybatisDetection {

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

    static String typeOf(org.springframework.context.annotation.ConditionContext ctx) {
        return ctx.getEnvironment().getProperty(PROP, AUTO);
    }

    static boolean mybatisPlusPresent(org.springframework.context.annotation.ConditionContext ctx) {
        return ClassUtils.isPresent(MYBATIS_PLUS_CLASS, ctx.getClassLoader());
    }

    static boolean mybatisSpringPresent(org.springframework.context.annotation.ConditionContext ctx) {
        return ClassUtils.isPresent(MYBATIS_SPRING, ctx.getClassLoader());
    }

    static IllegalStateException missingDependency(String type, String starter) {
        return new IllegalStateException("app.upload.metadata.type=" + type + " 需要引入依赖：" + starter);
    }
}
