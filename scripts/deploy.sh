#!/usr/bin/env bash
# 一键部署：本地打包源码 -> SSH 上传 -> 服务器上 docker compose 构建并启动
# 与 GitHub Actions 流水线执行的动作一致，可手动救急使用。
# 用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]
set -euo pipefail

HOST=${1:?用法: ./scripts/deploy.sh <user@host> [ssh端口] [远程目录]}
PORT=${2:-22}
REMOTE_DIR=${3:-/opt/resumable-upload}

echo "==> 打包并上传源码到 $HOST:$REMOTE_DIR"
ssh -p "$PORT" "$HOST" "mkdir -p '$REMOTE_DIR'"
tar --exclude='frontend/node_modules' \
    --exclude='frontend/dist' \
    --exclude='backend/target' \
    --exclude='backend/data' \
    --exclude='data' \
    --exclude='tmp' \
    --exclude='.git' \
    -czf - . | ssh -p "$PORT" "$HOST" "tar -xzf - -C '$REMOTE_DIR'"

echo "==> 服务器上构建并启动（首次拉取镜像与依赖约 5~10 分钟）"
ssh -p "$PORT" "$HOST" "cd '$REMOTE_DIR' && docker compose up -d --build && docker image prune -f"

echo "==> 健康检查"
for i in $(seq 1 60); do
  if ssh -p "$PORT" "$HOST" "curl -fsS http://localhost/api/files >/dev/null 2>&1"; then
    echo "==> 部署成功: http://${HOST#*@}"
    exit 0
  fi
  sleep 2
done
echo "==> 健康检查超时，请上服务器查看日志: docker compose -f $REMOTE_DIR/docker-compose.yml logs" >&2
exit 1
