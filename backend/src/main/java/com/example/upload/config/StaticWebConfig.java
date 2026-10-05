package com.example.upload.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 单容器部署模式：由 Spring Boot 直接托管前端构建产物（Vue 打包后的 dist），
 * 免去独立的 nginx 容器。/api/** 由 Controller 处理，其余路径走静态资源。
 * 仅当配置了 app.static-dir（Docker 里通过环境变量 STATIC_DIR 注入）时生效；
 * 本地开发仍用 vite dev server，无需配置。
 */
@Configuration
public class StaticWebConfig implements WebMvcConfigurer {

    private final String staticDir;

    public StaticWebConfig(@Value("${app.static-dir:}") String staticDir) {
        this.staticDir = staticDir == null ? "" : staticDir.trim();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 显式接管 "/"（Boot 默认的 welcome-page 映射只查 classpath，会 404），
        // forward 到 /index.html 后走同一个静态资源处理器
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (staticDir.isEmpty()) {
            return;
        }
        String location = staticDir.endsWith("/") ? staticDir : staticDir + "/";
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + location)
                .setCachePeriod(3600)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.isEmpty()) {
                            // 根路径直接映射到 index.html（空路径会被解析成目录资源）
                            resourcePath = "index.html";
                        }
                        Resource res = super.getResource(resourcePath, location);
                        if (res != null && res.getFile().isFile()) {
                            return res;
                        }
                        // SPA 回退：未命中的前端路由回退到 index.html；
                        // api 路径不回退，保持 404 语义
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        return location.createRelative("index.html");
                    }
                });
    }
}
