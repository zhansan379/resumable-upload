package com.example.upload.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无数据源护栏：mybatis starter 会把 spring-boot-starter-jdbc 传递进运行时类路径，
 * DataSourceAutoConfiguration 只要发现类路径上有连接池就会注册 dataSource 单例——
 * 宿主没配 spring.datasource.url 时整个应用直接启动失败，"零配置开箱即跑"被破坏。
 * <p>
 * 本过滤器在自动装配解析阶段生效：元数据不使用数据库（metadata.type 未显式指定 mybatis 系，
 * 且 spring.datasource.url 未配置）时，把 JDBC/MyBatis 系自动装配整组排除；
 * 一旦配置了数据源或显式指定 mybatis/mybatis-plus，则完全放行。
 * 注册于 META-INF/spring.factories（Spring Boot 的 AutoConfigurationImportFilter SPI）。
 */
public class NoDataSourceGuard implements AutoConfigurationImportFilter, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(NoDataSourceGuard.class);

    private static final Set<String> JDBC_STACK = Set.of(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceInitializationAutoConfiguration",
            "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration",
            "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration");

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean wantsDb = MybatisDetectionHelper.wantsDatabase(
                env.getProperty("app.upload.metadata.type", "auto"), env);
        boolean[] keep = new boolean[autoConfigurationClasses.length];
        boolean announced = false;
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String name = autoConfigurationClasses[i];
            if (!wantsDb && name != null && JDBC_STACK.contains(name)) {
                keep[i] = false;
                if (!announced) {
                    announced = true;
                    if (ANNOUNCED.compareAndSet(false, true)) {
                        log.info("未配置 spring.datasource.url，已排除 JDBC/MyBatis 自动装配，"
                                + "秒传索引回落 JSON 文件（单实例）；配置数据源后可切换为数据库元数据");
                    }
                }
            } else {
                keep[i] = true;
            }
        }
        return keep;
    }

    /** 仅作过滤判断用的小助手，避免与 MetadataStoreConfig 的条件类相互依赖 */
    private static class MybatisDetectionHelper {
        static boolean wantsDatabase(String type, Environment env) {
            if ("mybatis".equals(type) || "mybatis-plus".equals(type)) {
                return true;
            }
            return "auto".equals(type) && StringUtils.hasText(env.getProperty("spring.datasource.url"));
        }
    }
}
