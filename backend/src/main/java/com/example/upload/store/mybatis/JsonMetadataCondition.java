package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** type=json，或 auto 且类路径无任何 MyBatis 运行时 → JSON 实现（零依赖回落） */
public class JsonMetadataCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.JSON.equals(type)) {
            return true;
        }
        if (MybatisDetection.AUTO.equals(type)) {
            return !MybatisDetection.mybatisPlusPresent(ctx) && !MybatisDetection.mybatisSpringPresent(ctx);
        }
        return false;
    }
}
