package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** type=json；或 auto 且"MyBatis 系类路径存在但数据源未配置"→ JSON 实现（零配置回落） */
public class JsonMetadataCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.JSON.equals(type)) {
            return true;
        }
        if (MybatisDetection.AUTO.equals(type)) {
            boolean usable = MybatisDetection.mpRuntimeAvailable(ctx)
                    || MybatisDetection.mybatisRuntimeAvailable(ctx);
            if (!usable) {
                MybatisDetection.warnJsonFallback(ctx);
                return true;
            }
        }
        return false;
    }
}
