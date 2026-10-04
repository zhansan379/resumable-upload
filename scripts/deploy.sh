#!/usr/bin/env bash
# 一键部署（产物模式）：本地构建 jar/前端 -> 上传产物 -> 服务器组装轻量镜像并启动
# 服务器上只做 COPY 级镜像组装（秒级、几乎不占内存），不执行任何编译，适合小内存云主机。
# 用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]
set -euo pipefail

HOST=${1:?用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]}
PORT=${2:-22}
REMOTE_DIR=${3:-/opt/resumable-upload}
SSH="ssh -p $PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new $HOST"
SCP="scp -P $PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new"

echo "==> [1/5] 本地构建后端 jar"
( cd backend && mvn -q -B -DskipTests package )
JAR=$(ls backend/target/resumable-upload-backend-*.jar | head -1)

echo "==> [2/5] 本地构建前端 dist"
( cd frontend && npm run build --silent )

echo "==> [3/5] 上传产物与部署文件到 $HOST:$REMOTE_DIR"
$SSH "mkdir -p '$REMOTE_DIR/backend' '$REMOTE_DIR/frontend'"
$SCP "$JAR" "$HOST:$REMOTE_DIR/backend/app.jar"
$SCP backend/Dockerfile.artifact "$HOST:$REMOTE_DIR/backend/"
$SCP frontend/Dockerfile.artifact frontend/nginx.conf "$HOST:$REMOTE_DIR/frontend/"
tar -C frontend -czf - dist | $SSH "tar -xzf - -C '$REMOTE_DIR/frontend'"
$SCP docker-compose.prod.yml "$HOST:$REMOTE_DIR/"

echo "==> [4/5] 服务器组装镜像并启动（首次需拉取基础镜像，约 1~3 分钟）"
$SSH "cd '$REMOTE_DIR' \
  && docker build -q -t resumable-upload-backend -f backend/Dockerfile.artifact backend/ \
  && docker build -q -t resumable-upload-nginx  -f frontend/Dockerfile.artifact frontend/ \
  && docker compose -f docker-compose.prod.yml up -d"

echo "==> [5/5] 健康检查"
for i in $(seq 1 45); do
  if $SSH "curl -fsS http://localhost:8080/api/files >/dev/null 2>&1"; then
    echo "==> 部署成功: http://${HOST#*@}:8080"
    exit 0
  fi
  sleep 2
done
echo "==> 健康检查超时，登录服务器查看: docker compose -f $REMOTE_DIR/docker-compose.prod.yml logs" >&2
exit 1
