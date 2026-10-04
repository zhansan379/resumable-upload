# 大文件分片上传 · 断点续传 / 秒传

Java (Spring Boot 3) + Vue 3 实现的大文件上传方案：**分片上传、断点续传、秒传、暂停/恢复、并发控制、合并后 MD5 完整性校验**，零数据库依赖，开箱即跑。

方案设计基于对 GitHub 高 star 开源项目的调研，调研与分析详见 **[docs/01-开源项目调研分析.md](docs/01-开源项目调研分析.md)**（涵盖 uppy、blueimp/jQuery-File-Upload、filepond、fine-uploader、plupload、resumable.js、flow.js、simple-uploader、tus/tusd、free-fs 等 10+ 项目）。

## 功能清单

| 能力 | 说明 |
|------|------|
| 分片上传 | 前端按分片大小（默认 5MB）`Blob.slice` 切片并发上传（默认并发 3，可调 1~6） |
| 断点续传 | 上传前 check 接口一次返回服务端已收分片列表，跳过已传分片；状态以磁盘为事实源，**服务端重启、浏览器刷新均不丢进度** |
| 秒传 | 前端 spark-md5（Web Worker，2MB 增量）计算整文件 MD5，服务端 hash 索引命中直接返回，不传一字节 |
| 暂停 / 恢复 | AbortController 中断在途分片；恢复时重新 check 同步服务端状态 |
| 失败重试 | 网络 / 5xx 错误指数退避重试 3 次；4xx 永久错误直接失败 |
| 合并 | 全片完成后显式合并；`fileHash` 粒度加锁防并发合并；合并前校验分片齐全与总字节一致 |
| 完整性校验 | 合并完成后**后台重算整体 MD5** 与前端 hash 比对（不阻塞响应），结果在文件列表展示 |
| 文件管理 | 文件列表、下载、删除；取消上传自动清理服务端分片目录 |

## 快速启动

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

存储布局（`backend/data/upload/`，可用 `app.upload.base-dir` 修改）：

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

## 服务器部署（Docker Compose）

仓库内置了完整的容器化部署配置（`backend/Dockerfile`、`frontend/Dockerfile + nginx.conf`、`docker-compose.yml`）：

- 前端构建产物由 **nginx** 托管并反向代理 `/api` 到后端容器，对外只暴露 80 端口；
- 上传的分片与最终文件存在命名卷 `upload-data`（容器内 `/data/upload`，可用环境变量 `UPLOAD_DIR` 覆盖），重建容器不丢数据；
- 国内网络已内置阿里云 Maven 镜像与 npmmirror 加速。

在任意能 SSH 到服务器的机器上执行（服务器需已安装 Docker，脚本会完成：打包源码 → 上传 → `docker compose up -d --build` → 健康检查）：

```bash
./scripts/deploy.sh root@<服务器IP>          # 默认 22 端口，部署到 /opt/resumable-upload
./scripts/deploy.sh root@<服务器IP> 2222     # 自定义 SSH 端口
```

部署完成后验证后端接口（脚本同样适用于验证线上环境）：

```bash
BASE_URL=http://<服务器IP>/api node scripts/e2e-backend.mjs
```

常用运维命令（服务器上）：

```bash
cd /opt/resumable-upload
docker compose ps                 # 状态
docker compose logs -f backend    # 后端日志
docker compose up -d --build      # 代码更新后重建
docker compose down               # 停止（数据卷保留）
```

## CI/CD（GitHub Actions）

`.github/workflows/deploy.yml`：push 到 `main` 分支（或手动触发）自动执行——

1. 在 GitHub Runner 上打包源码（排除 node_modules/target 等构建产物）；
2. 通过 SSH（私钥存于仓库 Secrets）上传到服务器 `/opt/resumable-upload`；
3. 服务器上 `docker compose up -d --build` 重建容器；
4. 循环探测 `/api/files` 健康检查，成功/失败都会反馈到 Actions 日志。

需要在仓库 **Settings → Secrets and variables → Actions** 配置 4 个 Secrets：

| Secret | 说明 |
|--------|------|
| `SSH_HOST` | 服务器公网 IP |
| `SSH_USER` | SSH 用户名（如 root） |
| `SSH_PORT` | SSH 端口（可选，默认 22） |
| `SSH_PRIVATE_KEY` | 用于部署的 SSH **私钥**完整内容（`-----BEGIN OPENSSH PRIVATE KEY-----` 起） |

生成专用部署密钥（不要复用个人密钥）：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/resumable-upload-deploy -N "" -C "resumable-upload-deploy"
# 把公钥追加到服务器：
ssh-copy-id -i ~/.ssh/resumable-upload-deploy.pub root@<服务器IP>   # 或手动追加到 ~/.ssh/authorized_keys
# 私钥内容写入 Secret：
gh secret set SSH_PRIVATE_KEY < ~/.ssh/resumable-upload-deploy
```

安全组要求：入方向放行 `22/TCP`（建议限制源地址为你的出口 IP）与 `80/TCP`（0.0.0.0/0）。未配置 Secrets 时流水线会自动跳过部署，不会报错。

## 生产化建议（超出 Demo 范围）

- 元数据入 MySQL/PostgreSQL，分片状态入 Redis（参照 free-fs 的任务状态机 + 分布式锁）；
- 预签名 URL 直传对象存储（MinIO/OSS/S3），后端零带宽转发（参照 KKJava1 方案）；
- tus 协议落地可直接用 [tomdesair/tus-java-server](https://github.com/tomdesair/tus-java-server) + Uppy 前端；
- 分片目录孤儿清理定时任务、上传鉴权、限流、秒传引用计数。
