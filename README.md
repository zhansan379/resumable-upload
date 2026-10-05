# 大文件分片上传 · 断点续传 / 秒传

Java (Spring Boot 3) + Vue 3 实现的大文件上传方案：**分片上传、断点续传、秒传、暂停/恢复、并发控制、合并后 MD5 完整性校验**，零数据库依赖，开箱即跑。

方案设计基于对 GitHub 高 star 开源项目的调研，调研与分析详见 **[docs/01-开源项目调研分析.md](docs/01-开源项目调研分析.md)**（涵盖 uppy、blueimp/jQuery-File-Upload、filepond、fine-uploader、plupload、resumable.js、flow.js、simple-uploader、tus/tusd、free-fs 等 10+ 项目）。

## 功能清单

| 能力 | 说明 |
|------|------|
| 分片上传 | 前端按分片大小（默认 5MB）`Blob.slice` 切片并发上传（默认并发 3，可调 1~6） |
| 断点续传 | 上传前 check 接口一次返回服务端已收分片列表，跳过已传分片；状态以磁盘为事实源，**服务端重启、浏览器刷新均不丢进度** |
| 刷新恢复 | 任务列表持久化（localStorage 存元数据 + IndexedDB 存文件引用），刷新/重开页面后任务自动恢复：上传中的任务**自动续传且跳过已算好的 MD5**；主动暂停的任务恢复为待继续；文件引用失效时提示重选同名文件 |
| 秒传 | 前端 spark-md5（Web Worker，2MB 增量）计算整文件 MD5，服务端 hash 索引命中直接返回，不传一字节 |
| 暂停 / 恢复 | AbortController 中断在途分片；恢复时重新 check 同步服务端状态 |
| 失败重试 | 网络 / 5xx 错误指数退避重试 3 次；4xx 永久错误直接失败 |
| 合并 | 全片完成后显式合并；`fileHash` 粒度加锁防并发合并；合并前校验分片齐全与总字节一致 |
| 完整性校验 | 合并完成后**后台重算整体 MD5** 与前端 hash 比对（不阻塞响应），结果在文件列表展示 |
| 文件管理 | 文件列表、下载、删除；取消上传自动清理服务端分片目录 |

## 快速启动（本地开发）

要求：JDK 17+、Maven 3.9+、Node 18+。

```bash
# 1. 启动后端（端口 8080）
cd backend
mvn spring-boot:run

# 2. 启动前端（端口 5173，/api 代理到 8080）
cd frontend
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>，拖入大文件即可体验。重复上传同一文件可看到「秒传」；上传中刷新页面后重新添加同一文件可看到断点续传（已传分片被跳过）。

生产构建：

```bash
cd backend && mvn -DskipTests package    # target/resumable-upload-backend-1.0.0.jar
cd frontend && npm run build             # dist/
```

## 架构与上传流程

```
Vue 3 前端                                Spring Boot 后端
──────────────                            ──────────────────
1. spark-md5 Worker 计算整文件 MD5   ─┐
                                     │
2. POST /api/upload/check ───────────┼──>  hash 索引命中？ ── 是 ──> 返回 finished=true（秒传）
                                     │         │ 否
                                     │         └──> 扫描分片目录返回 uploadedChunks
3. 并发上传分片（并发3，失败重试）    │
   POST /api/upload/chunk ───────────┼──> 临时文件 + 原子移动 -> chunks/{fileHash}/{i}.part
4. 全片完成 POST /api/upload/merge ──┼──> 校验分片齐全/总字节 -> FileChannel 顺序合并
                                     │         -> files/{yyyyMM}/{hash}_{name}
                                     │         -> 写 hash 索引 -> 删分片目录
                                     │         -> 后台重算 MD5 校验（异步）
5. 展示进度 / 速度 / 状态            ─┘
```

存储布局（`UPLOAD_DIR` 指向的目录，默认 `./data/upload`）：

```
data/upload/
├── chunks/{fileHash}/{chunkIndex}.part   # 分片临时目录（0 起编号）
├── files/{yyyyMM}/{fileHash}_{fileName}  # 合并后的最终文件
└── index.json                            # hash 索引（秒传依据，服务重启不丢）
```

## API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload/check` | 秒传 + 断点探测。入参 `{fileHash, fileName, totalSize, totalChunks, chunkSize}`；返回 `{finished, uploadedChunks, url, ...}` |
| POST | `/api/upload/chunk` | 上传单个分片（multipart：`fileHash/chunkIndex/totalChunks/chunkSize/totalSize/fileName/chunk`） |
| POST | `/api/upload/merge` | 合并请求，入参同 check；分片缺失返回 409 |
| GET | `/api/files` | 已上传文件列表 |
| GET | `/api/files/{fileHash}/download` | 下载（支持中文文件名） |
| DELETE | `/api/files/{fileHash}` | 删除文件及索引 |
| DELETE | `/api/upload/{fileHash}` | 取消上传，清理分片目录 |

错误码语义（参考 tus 协议）：`400` 参数非法 / `409` 分片缺失、状态冲突 / `413` 超过大小上限 / `415` 分片超限 / `500` 服务端错误。前端仅对网络错误与 5xx 重试，4xx 直接失败。

## 关键实现

**前端（`frontend/src/`）**

- `upload/md5Worker.js`：spark-md5 在 Web Worker 中按 2MB 分片增量计算整文件 MD5，带进度，不撑爆内存；
- `upload/uploader.js`：调度器。并发工作循环（默认 3）从待传队列取分片；AbortController 支持暂停/取消；4xx 永久错误与可重试错误分类（uppy 的 retryDelays 思想）；全片完成调合并端点（fine-uploader success.endpoint 思想）；
- `App.vue`：拖拽上传、任务表（进度/速度/状态）、服务器文件管理。

**后端（`backend/src/main/java/com/example/upload/`）**

- `service/UploadService.java`：核心。分片写入「临时文件 + 原子移动」杜绝半片；合并按分片号顺序 `FileChannel.transferTo` 追加（兼容乱序到达）；`fileHash` 粒度锁防并发合并；合并后校验总字节，后台单线程重算 MD5（弥补多数开源实现缺失的完整性校验）；
- `store/HashIndexStore.java`：hash 索引，临时文件 + 原子移动持久化到 `index.json`，秒传依据与服务端重启恢复；
- `controller/UploadController.java`：协议端点；`exception/GlobalExceptionHandler.java`：统一错误码。

## 与开源项目吸收点对照

| 借鉴来源 | 吸收点 |
|----------|--------|
| simple-uploader.js + free-fs | check 一次返回已传分片列表（避免逐片 GET）；前端 MD5 作为唯一标识 |
| resumable.js | 分片参数协议（chunkIndex/totalChunks/chunkSize/totalSize/identifier） |
| fine-uploader | 显式合并端点；localStorage 思路（本实现改由服务端磁盘承担断点状态，更可靠） |
| tus 协议 | 状态码语义（409/412/413/415）、数据与元数据分离 |
| blueimp | 服务端权威进度（分片状态以磁盘为准） |
| filepond | revert 端点（取消上传清理分片） |

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

## 生产化建议（超出 Demo 范围）

- 元数据入 MySQL/PostgreSQL，分片状态入 Redis（参照 free-fs 的任务状态机 + 分布式锁）；
- 预签名 URL 直传对象存储（MinIO/OSS/S3），后端零带宽转发（参照 KKJava1 方案）；
- tus 协议落地可直接用 [tomdesair/tus-java-server](https://github.com/tomdesair/tus-java-server) + Uppy 前端；
- 分片目录孤儿清理定时任务、上传鉴权、限流、秒传引用计数。
