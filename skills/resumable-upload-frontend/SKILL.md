---
name: resumable-upload-frontend
description: 将 resumable-upload 前端上传组件（headless 调度核心 + Vue3 上传面板）复制集成到用户的前端项目。当用户要求在前端接入大文件上传/分片上传/断点续传/秒传组件时使用。后端未集成时先走 resumable-upload-integration。
---

# resumable-upload 前端集成 Skill

把 [zhansan379/resumable-upload](https://github.com/zhansan379/resumable-upload) 的前端组件以**复制源码**方式集成进宿主前端。手册：[docs/07-组件集成指南.md](https://raw.githubusercontent.com/zhansan379/resumable-upload/main/docs/07-%E7%BB%84%E4%BB%B6%E9%9B%86%E6%88%90%E6%8C%87%E5%8D%97.md)（中文路径已 URL 编码）。**后端还没集成时先走 `resumable-upload-integration`，本 Skill 只管前端。**

## 第一步：必答题（动手前确认，涉及宿主架构的必须先问用户）

1. **框架**：Vue3 / React / uniapp / 其他（原生 JS）？——决定复制什么、改造多少（见下）；
2. **要不要 UI**：直接用组件面板（仅 Vue3）还是 headless 核心 + 自绘 UI；
3. **上传模式**：服务端中转（当前组件默认，开箱即用）还是预签名直传（后端需开 `s3.direct-upload`，**前端未内置直传适配**，需要你按手册 5.5 的 init/part-urls 流程改造 api 层——如实告知工作量，不要假装复制完就支持直传）；
4. **API 前缀与鉴权**：后端前缀是什么？宿主请求怎么带 token（axios 拦截器示例见下）；
5. **分片大小**：后端是 S3 系存储时非末片 ≥ 5MiB，前端 `chunk-size-mb` 必须 ≥ 5。

## 文件清单（从仓库 frontend/src/ 复制）

| 文件 | 角色 | 何时复制 |
|------|------|----------|
| `upload/uploader.js` | headless 调度核心：并发循环、重试、弱网 AIMD 降并发、暂停恢复、秒传 | 必须 |
| `upload/md5Worker.js` | spark-md5 Web Worker（主线程算 MD5 会卡死大文件页面） | 必须 |
| `upload/taskStore.js` | IndexedDB + localStorage 任务持久化（刷新恢复的契约） | 必须 |
| `api.js` | 全部服务端调用（axios），**适配点最集中的文件** | 必须（按宿主改造） |
| `utils/format.js` | 字节/速度/状态文案 | 用面板时必须 |
| `components/UploadPanel.vue` | 自带 UI 的上传面板（无任何 UI 库依赖，scoped 样式） | 仅 Vue3 且要 UI |

依赖：`axios`、`spark-md5`。**复制后同步安装这两个依赖。**

## 按框架执行

- **Vue3**：复制全部 6 个文件 → 设置 `window.__UPLOAD_API_PREFIX__`（或改 api.js 的 baseURL）→ 挂鉴权拦截器 → `<UploadPanel :chunk-size-mb="5" :concurrency="3" @uploaded="..." @error="..." />` → 按宿主设计规范改 scoped 样式。参考仓库 `frontend/src/App.vue`（它就是消费方）。
- **React / 其他**：复制 4 个 headless 文件（UploadPanel.vue 仅作行为参考阅读）。核心 API：
  `new Uploader(file, { chunkSize, concurrency, initialHash, onEvent })`，
  事件 `state / hash / hash-progress / progress / concurrency / done / error`，
  方法 `start / pause / resume / retry / cancel / peekProgress`（刷新恢复语义见 App.vue 的 onMounted）。
  用 React 状态机把这些事件渲染出来即可；`import.meta.url` Worker 写法按宿主构建工具适配（Vite/webpack5 原生支持）。
- **uniapp**：**这是移植不是复制**，如实告知工作量：axios → `uni.request`/`uni.uploadFile`；Web Worker 不可用 → MD5 需改为主线程分块增量计算或平台替代方案（大文件性能会降）；IndexedDB → `uni.setStorage`（文件引用恢复能力受限）；浏览器专有的 drag-drop 需换 uni 文件选择。逐项改造后跑验证。

## 鉴权接线（最常见适配）

```js
// api.js 里给 axios 实例加请求拦截器，把宿主的 token 带上
http.interceptors.request.use((config) => {
  config.headers.Authorization = `Bearer ${getHostToken()}`
  return config
})
```

## 验证（不许跳过）

后端先按 `resumable-upload-integration` 的验证协议跑通。前端 `npm run dev` 后按清单手动验证（每项都要做）：

1. 选一个大文件（≥200MB）→ 进度条/速度正常，MD5 计算先于上传；
2. 传到 30% 点暂停 → 再点继续 → 从 30% 附近继续（不是从 0）；
3. 传到一半**刷新页面** → 任务自动回来并续传（不是从 0）；
4. 同一文件再选一次 → 秒传（立即完成，不产生新流量）；
5. 传完的任务在服务器列表可见；取消任务后服务器分片被清理；
6. dev 模式可用 `window.__uploadTasks` 检查任务状态机。

全部符合才算完成；失败先对照后端手册第 7 节故障表与浏览器 Network 面板。

## 硬规则

- 协议字段（`fileHash/chunkIndex/totalChunks/totalSize`）与事件状态机（waiting→hashing→checking→uploading→merging→done/error）不可改动；
- MD5 必须留在 Worker；`taskStore` 的 localStorage 键与 IndexedDB 结构是刷新恢复的契约，改动会丢用户任务；
- 不要删除 `peekProgress`/`markInterrupted`——刷新恢复与"分片已齐不自动合并"语义靠它们；
- 前端只消费协议，发现"后端行为与手册不符"时修后端，不改前端协议凑合。
