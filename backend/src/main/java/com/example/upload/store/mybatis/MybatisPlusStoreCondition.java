package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** type=mybatis-plus（显式，缺依赖快速失败），或 auto 且类路径存在 MyBatis-Plus */
public class MybatisPlusStoreCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.MYBATIS_PLUS.equals(type)) {
            if (!MybatisDetection.mybatisPlusPresent(ctx)) {
                throw MybatisDetection.missingDependency(type,
                        "com.baomidou:mybatis-plus-spring-boot3-starter");
            }
            return true;
        }
        return MybatisDetection.AUTO.equals(type) && MybatisDetection.mybatisPlusPresent(ctx);
    }
}
