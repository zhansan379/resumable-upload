# ============================================================
# 单镜像自包含构建：前端 dist + 后端 jar 打进同一个镜像，
# 由 Spring Boot 单进程同时提供 API 与静态资源。
# 适用：本地/大内存环境 docker compose up -d --build 一键构建。
# CI 与小内存服务器请用 Dockerfile.artifact（产物模式）。
# ============================================================

# ---- 前端构建 ----
FROM node:22-alpine AS web
WORKDIR /web
RUN npm config set registry https://registry.npmmirror.com
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY frontend/ .
ENV NODE_OPTIONS="--max-old-space-size=768"
RUN npm run build

# ---- 后端构建 ----
FROM maven:3.9-eclipse-temurin-17 AS build
ENV MAVEN_OPTS="-Xmx512m"
WORKDIR /app
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
      '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
      '  <mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf>' \
      '  <url>https://maven.aliyun.com/repository/public</url></mirror></mirrors>' \
      '</settings>' > /root/.m2/settings.xml
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# ---- 运行：单进程 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/resumable-upload-backend-*.jar app.jar
COPY --from=web /web/dist ./static
ENV UPLOAD_DIR=/data/upload \
    STATIC_DIR=/app/static \
    JAVA_OPTS="-Xms64m -Xmx256m"
VOLUME ["/data/upload"]
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
