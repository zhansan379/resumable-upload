package com.example.upload.store.mybatis;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 合并锁选择：元数据走 MyBatis/MyBatis-Plus（多实例语义）时使用数据库租约锁；
 * 其余情况（json，含 auto 回落）由本地监视器锁兜底（MetadataStoreConfig 中 @ConditionalOnMissingBean 回落）。
 */
public class MybatisMergeLockCondition implements Condition {

    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
        String type = MybatisDetection.typeOf(ctx);
        if (MybatisDetection.MYBATIS.equals(type) || MybatisDetection.MYBATIS_PLUS.equals(type)) {
            return true;
        }
        return MybatisDetection.AUTO.equals(type)
                && (MybatisDetection.mpRuntimeAvailable(ctx)
                    || MybatisDetection.mybatisRuntimeAvailable(ctx));
    }
}
