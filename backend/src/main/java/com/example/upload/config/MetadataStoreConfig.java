package com.example.upload.config;

import com.example.upload.service.HeaderTenantResolver;
import com.example.upload.service.TenantResolver;
import com.example.upload.store.local.JsonMetadataStore;
import com.example.upload.store.local.LocalMergeLock;
import com.example.upload.store.mybatis.MybatisMergeLock;
import com.example.upload.store.mybatis.MybatisMergeLockCondition;
import com.example.upload.store.mybatis.MybatisMetadataStore;
import com.example.upload.store.mybatis.MybatisPlusMetadataStore;
import com.example.upload.store.mybatis.MybatisPlusStoreCondition;
import com.example.upload.store.mybatis.MybatisStoreCondition;
import com.example.upload.store.mybatis.JsonMetadataCondition;
import com.example.upload.store.mybatis.UploadFileLockMapper;
import com.example.upload.store.mybatis.UploadFileRecordMapper;
import com.example.upload.store.mybatis.UploadFileRecordPlusMapper;
import com.example.upload.store.spi.MergeLock;
import com.example.upload.store.spi.MetadataStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 元数据实现与合并锁的装配。type 由 app.upload.metadata.type 决定：
 * json / mybatis / mybatis-plus / auto（按类路径探测，默认）。
 * MergeLock：MyBatis 系 → 数据库租约锁（多实例）；否则本地监视器锁兜底。
 */
@Configuration
public class MetadataStoreConfig {

    @Bean
    @Conditional(JsonMetadataCondition.class)
    public JsonMetadataStore jsonMetadataStore(UploadProperties props) {
        return new JsonMetadataStore(props);
    }

    @Bean
    @Conditional(MybatisStoreCondition.class)
    public MybatisMetadataStore mybatisMetadataStore(UploadFileRecordMapper mapper) {
        return new MybatisMetadataStore(mapper);
    }

    @Bean
    @Conditional(MybatisPlusStoreCondition.class)
    public MybatisPlusMetadataStore mybatisPlusMetadataStore(UploadFileRecordPlusMapper mapper) {
        return new MybatisPlusMetadataStore(mapper);
    }

    @Bean
    @Conditional(MybatisMergeLockCondition.class)
    public MybatisMergeLock mybatisMergeLock(UploadFileLockMapper mapper) {
        return new MybatisMergeLock(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(MergeLock.class)
    public LocalMergeLock localMergeLock() {
        return new LocalMergeLock();
    }

    /** 租户来源：默认从请求头读取；宿主已有租户体系（JWT/SecurityContext/ThreadLocal 等）时注册自己的 TenantResolver Bean 覆盖 */
    @Bean
    @ConditionalOnMissingBean(TenantResolver.class)
    public HeaderTenantResolver headerTenantResolver(UploadProperties props) {
        return new HeaderTenantResolver(props);
    }
}
