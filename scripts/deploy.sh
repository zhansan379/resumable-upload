#!/usr/bin/env bash
# 一键部署（产物模式）：本地构建 jar/前端 -> 上传产物 -> 服务器组装单镜像并启动。
# 单容器：Spring Boot 同时提供 /api 与前端静态资源（无 nginx）。
# 服务器上只做 COPY 级镜像组装（秒级、几乎不占内存），不执行任何编译。
# 用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]
set -euo pipefail

HOST=${1:?用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]}
PORT=${2:-22}
REMOTE_DIR=${3:-/opt/resumable-upload}
KEY=${DEPLOY_KEY:-$HOME/.ssh/resumable-upload-deploy}
SSH="ssh -i $KEY -p $PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new $HOST"
SCP="scp -i $KEY -P $PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new"

echo "==> [1/5] 本地构建后端 jar"
( cd backend && mvn -q -B -DskipTests package )
JAR=$(ls backend/target/resumable-upload-backend-*.jar | head -1)

echo "==> [2/5] 本地构建前端 dist"
( cd frontend && npm run build --silent )

echo "==> [3/5] 打包产物并上传到 $HOST:$REMOTE_DIR"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/app.jar"
cp -r frontend/dist "$STAGE/static"
cp Dockerfile.artifact docker-compose.prod.yml "$STAGE/"
tar -C "$STAGE" -czf - . | $SSH "mkdir -p '$REMOTE_DIR' && tar -xzf - -C '$REMOTE_DIR'"

echo "==> [4/5] 服务器组装镜像并启动（首次需拉取基础镜像，约 1~3 分钟）"
$SSH '
  base() {
    docker images --format "{{.Repository}}:{{.Tag}}" | grep -qx "$1" && return 0
    docker pull -q "$1" 2>/dev/null && return 0
    for M in docker.1ms.run docker.m.daocloud.io; do
      docker pull -q "$M/library/$1" 2>/dev/null && { docker tag "$M/library/$1" "$1" && return 0; }
    done
    echo "基础镜像 $1 拉取失败" >&2; return 1
  }
  cd '"$REMOTE_DIR"' || exit 1
  base eclipse-temurin:17-jre \
    && docker build -q -t resumable-upload:latest -f Dockerfile.artifact . \
    && docker compose -f docker-compose.prod.yml up -d --remove-orphans
'

echo "==> [5/5] 健康检查"
for i in $(seq 1 45); do
  if $SSH "curl -fsS http://localhost:8080/api/files >/dev/null 2>&1 && curl -fsS http://localhost:8080/ >/dev/null"; then
    echo "==> 部署成功: http://${HOST#*@}:8080"
    exit 0
  fi
  sleep 2
done
echo "==> 健康检查超时，请上服务器查看: docker compose -f $REMOTE_DIR/docker-compose.prod.yml logs" >&2
exit 1
