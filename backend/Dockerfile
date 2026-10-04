# ---- 构建阶段：Maven + JDK 17 ----
FROM maven:3.9-eclipse-temurin-17 AS build
# 小内存机器上构建的堆上限保护
ENV MAVEN_OPTS="-Xmx512m"
WORKDIR /app
# 服务器在国内时用阿里云 Maven 镜像加速依赖下载
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
      '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
      '  <mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf>' \
      '  <url>https://maven.aliyun.com/repository/public</url></mirror></mirrors>' \
      '</settings>' > /root/.m2/settings.xml
COPY pom.xml .
# 先只拷 pom 预拉依赖，充分利用 Docker 层缓存
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/resumable-upload-backend-*.jar app.jar
ENV UPLOAD_DIR=/data/upload \
    JAVA_OPTS="-Xms256m -Xmx768m"
VOLUME ["/data/upload"]
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
