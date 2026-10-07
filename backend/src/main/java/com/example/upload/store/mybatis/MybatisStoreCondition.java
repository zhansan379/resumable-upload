package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** type=mybatis（显式，缺依赖快速失败），或 auto 且类路径只有 MyBatis 没有 MyBatis-Plus */
public class MybatisStoreCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.MYBATIS.equals(type)) {
            if (!MybatisDetection.mybatisSpringPresent(ctx)) {
                throw MybatisDetection.missingDependency(type, "org.mybatis.spring.boot:mybatis-spring-boot-starter");
            }
            return true;
        }
        return MybatisDetection.AUTO.equals(type)
                && MybatisDetection.mybatisSpringPresent(ctx)
                && !MybatisDetection.mybatisPlusPresent(ctx);
    }
}
