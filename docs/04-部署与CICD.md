# 部署与 CI/CD

> 返回 [README](../README.md)

## 服务器部署（Docker，单容器 + 产物模式）

**单容器架构**：Spring Boot 单进程同时提供 `/api` 接口与前端静态资源（`StaticWebConfig` 托管 dist，含 SPA 回退与 gzip 压缩），无独立 nginx。针对小内存云主机设计：**本地/CI 构建产物，服务器只做 COPY 级镜像组装与运行**，全程不在服务器上编译。

```bash
# 一键部署（本机需 JDK17/Maven/Node，服务器需 Docker）：
./scripts/deploy.sh root@<服务器IP>          # 默认 22 端口，部署到 /opt/resumable-upload
./scripts/deploy.sh root@<服务器IP> 2222     # 自定义 SSH 端口
```

脚本动作：本地 `mvn package` + `npm run build` → 上传 jar/static/部署文件 → 服务器 `docker build -f Dockerfile.artifact`（纯 COPY，秒级）→ `docker compose -f docker-compose.prod.yml up -d` → 健康检查。

要点：

- **对外端口 `8080`**，避免与同机 80 端口的现有业务冲突；
- 容器内存上限 512M（`-Xmx256m`），不挤占同机其他服务；
- 上传数据持久化在命名卷 `upload-data`，重建容器不丢；
- `Dockerfile.artifact`（根目录）为薄运行时镜像；`Dockerfile`（根目录）为自包含构建（本地/大内存环境可用：`docker compose up -d --build`，内部完成前后端构建）。

验证部署（e2e 脚本同时适用于本地与线上）：

```bash
BASE_URL=http://<服务器IP>:8080/api node scripts/e2e-backend.mjs
```

运维命令（服务器上）：

```bash
cd /opt/resumable-upload
docker compose -f docker-compose.prod.yml ps          # 状态
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml up -d       # 拉取新镜像后重启
docker compose -f docker-compose.prod.yml down        # 停止（数据卷保留）
```

安全组要求：入方向放行 `22/TCP`（建议限制源地址）与 `8080/TCP`（0.0.0.0/0）。

## CI/CD（GitHub Actions）

`.github/workflows/deploy.yml`：push 到 `main`（或手动触发）自动执行，总时长约 **3 分钟**——

1. Runner 上 `setup-java(17)` / `setup-node(22)` 编译后端 jar 与前端 dist；
2. 用 `Dockerfile.artifact` 组装**单镜像**并**推送到阿里云 ACR**（个人版免费；同时保留 `sha` tag 便于回滚）；
3. 服务器从 ACR **同地域 VPC 内网地址**拉取镜像（秒级），`docker compose up -d`；
4. 循环探测 `/api/files` 与首页健康检查，结果反馈到 Actions 日志。

> 为什么不直接从 Runner SSH 传产物/镜像：GitHub 海外 Runner 到国内 ECS 的 SSH 实测仅约 40KB/s；ACR 注册表走并行分块上传 + 服务器内网拉取，吞吐高一个数量级。

需要在仓库 **Settings → Secrets and variables → Actions** 配置 Secrets：

| Secret | 说明 |
|--------|------|
| `SSH_HOST` | 服务器公网 IP |
| `SSH_USER` | SSH 用户名（如 root） |
| `SSH_PORT` | SSH 端口（可选，默认 22） |
| `SSH_PRIVATE_KEY` | 用于部署的 SSH **私钥**完整内容 |
| `ACR_REGISTRY` | ACR 访问域名（如 `crpi-xxx.cn-beijing.personal.cr.aliyuncs.com`） |
| `ACR_NAMESPACE` | ACR 命名空间 |
| `ACR_DOCKER_USERNAME` / `ACR_DOCKER_PASSWORD` | ACR 访问凭证（"访问凭证"页设置的固定密码） |

生成专用部署密钥（不要复用个人密钥）：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/resumable-upload-deploy -N "" -C "resumable-upload-deploy"
ssh-copy-id -i ~/.ssh/resumable-upload-deploy.pub root@<服务器IP>
gh secret set SSH_PRIVATE_KEY < ~/.ssh/resumable-upload-deploy
```

本地应急部署可走 `scripts/deploy.sh`（产物直传模式，不依赖 ACR）。未配置 Secrets 时流水线会自动跳过部署，不会报错。
