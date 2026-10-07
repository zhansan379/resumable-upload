package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** type=mybatis（显式：缺依赖或缺数据源都快速失败并给出建议），或 auto 且仅纯 MyBatis 运行时可用 */
public class MybatisStoreCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.MYBATIS.equals(type)) {
            if (!MybatisDetection.mybatisSpringPresent(ctx)) {
                throw MybatisDetection.missingDependency(type, "org.mybatis.spring.boot:mybatis-spring-boot-starter");
            }
            if (!MybatisDetection.datasourceConfigured(ctx)) {
                throw MybatisDetection.missingDatasource(type);
            }
            return true;
        }
        return MybatisDetection.AUTO.equals(type)
                && MybatisDetection.mybatisRuntimeAvailable(ctx)
                && !MybatisDetection.mpRuntimeAvailable(ctx);
    }
}
