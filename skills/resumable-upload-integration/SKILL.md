---
name: resumable-upload-integration
description: 将 resumable-upload 大文件上传组件以复制源码方式集成到用户项目——后端（Spring Boot 3：分片/断点续传/秒传/可插拔存储/多实例/多租户）与前端（Vue3/React/uniapp 上传组件）一并覆盖。当用户要求集成大文件上传、分片上传、断点续传、秒传组件（后端、前端或两者）时使用。
---

# resumable-upload 组件集成 Skill

把 [zhansan379/resumable-upload](https://github.com/zhansan379/resumable-upload) 组件以**复制源码**方式接入宿主：后端（Spring Boot 3）与前端（Vue3/React/uniapp/原生 JS）一个流程覆盖。完整手册：[docs/07-组件集成指南.md](https://raw.githubusercontent.com/zhansan379/resumable-upload/main/docs/07-%E7%BB%84%E4%BB%B6%E9%9B%86%E6%88%90%E6%8C%87%E5%8D%97.md)（中文路径已 URL 编码）。

## 执行流程

1. **拿手册**（任选其一）：
   ```bash
   # 中文路径需 URL 编码，直接用下面这条：
   curl -sL "https://raw.githubusercontent.com/zhansan379/resumable-upload/main/docs/07-%E7%BB%84%E4%BB%B6%E9%9B%86%E6%88%90%E6%8C%87%E5%8D%97.md"
   ```
   或从用户提供的仓库副本直接读。
2. **前置判断**：后端集成要求 Spring Boot 3.x + Java 17（不满足 → 停止并说明，不做降级尝试）；
   前端核心要求能跑 Web Worker 与 IndexedDB 的浏览器环境（uniapp 属于移植，工作量见下）。
3. **集成范围**：与用户确认这次接什么（□ 仅后端 □ 仅前端 □ 前后端都要）。前端依赖后端，
   全量集成时先做后端、验证通过后再做前端。
4. **必答题**（动手前问用户或读宿主代码确认，能自动探索的先探索再确认）：
   - 后端：API 前缀；鉴权体系怎么接；存储选型（local / MinIO·RustFS 自建 / 云上 OSS·COS·OBS·S3——S3 要拿到 endpoint/凭证/桶名）；`metadata.type`（类路径有 MyBatis-Plus/MyBatis 且配了数据源 → auto 会自动用库，明确告知用户）；**宿主是否已有多租户体系**（JWT/SecurityContext/ThreadLocal/MP TenantLineHandler——有则实现 `TenantResolver` 对接，禁止默认要求前端传组件自己的租户头）；
   - 前端：框架（Vue3 → 直接复制 `UploadPanel` 面板；React/其他 → headless 核心 + 自绘 UI；uniapp → 移植：axios→uni.request/uploadFile、Web Worker 不可用需换 MD5 方案、IndexedDB→uni.setStorage，工作量如实评估）；要不要 UI；token 注入方式；分片大小（S3 系后端非末片 ≥ 5MiB）；
   - 直传模式：`s3.direct-upload` 只覆盖后端端点（init/part-urls），**前端直传适配未内置**——用户要直传时按手册 5.5 改造 api 层，如实告知工作量，不要假装复制完就支持。
5. **执行**：
   - 后端：按手册 §2 清单复制（⛔ `UploadApplication`/`StaticWebConfig`/`App.vue` 等演示壳不复制）→ §3 步骤（**宿主主类补 `@ConfigurationPropertiesScan` + `@EnableScheduling`**，最高频失败原因）→ §5 适配点（前缀/鉴权拦截器/租户/多实例元数据）；
   - 前端：按手册 §4 清单复制 → api.js 改前缀、挂 token 请求拦截器 → Vue3 直接用 `<UploadPanel>`（事件 uploaded/error，参数 chunk-size-mb/concurrency）；React/其他用 headless 核心（`new Uploader(file, { chunkSize, concurrency, onEvent })`，事件与方法清单在 §4，刷新恢复语义参照 App.vue 的 onMounted）。
6. **验证**：后端跑手册 §6 curl 协议**逐项汇报**；前端按清单手动过：进度/速度正常 → 暂停后从断点继续（不是从 0）→ 刷新页面任务恢复续传 → 同文件秒传 → 取消清理服务器分片（dev 模式可查 `window.__uploadTasks`）。任何一步不符，查手册 §7 故障表，修完重跑全链，**不许跳过验证宣布完成**。

## 宿主环境适配检查（实战踩坑补充，复制源码前后各过一遍）

上游手册覆盖"组件要什么"，本节覆盖"宿主环境会怎么坑组件"（RuoYi-Vue 实战复盘，2026-10）：

**复制前——按宿主技术栈裁剪**：
1. 探测宿主 ORM/存储栈：类路径没有 `com.baomidou`（纯 MyBatis 宿主如 RuoYi）→ 剔除 `store/mybatisplus` 系全部文件、`UploadFileRecordEntity`，并删掉 `MetadataStoreConfig` 里的 MP Bean；不用 S3/直传 → 剔除 `store/s3/`、`DirectUpload*`。留着必编译失败；
2. `NoDataSourceGuard` 认的是 `spring.datasource.url` 字面属性：宿主数据源走 Druid 嵌套配置（`spring.datasource.druid.master.url`）时，guard 会误判"无数据源"并排除宿主必需的自动装配 → **不复制该文件**，改为在宿主配置里加 `spring.datasource.url` 别名专供 `metadata.type=auto` 探测（宿主已排除 DataSourceAutoConfiguration 时别名无副作用）；
3. 核对宿主 `@MapperScan`/`@ComponentScan` 覆盖范围：如 RuoYi 只扫 `com.ruoyi.**.mapper`，组件 Mapper 在 `store.mybatis` 包 → 在组件集成配置类上补 `@MapperScan("组件.mybatis包")`。

**复制后——防宿主全局副作用传染**：
4. 全局搜组件与宿主同名的 `@Component/@RestControllerAdvice/Bean`（典型：`GlobalExceptionHandler`）→ 冲突则组件侧改名（含 LoggerFactory 自引用），否则 `ConflictingBeanDefinitionException`；
5. Windows 下凡用脚本重写/生成过 `.java`/`.vue` 文件，编译报 `非法字符: '﻿'` 即 BOM：PS 5.1 `Set-Content -Encoding UTF8` 会带 BOM，一律用 `[IO.File]::WriteAllText($f,$t,[Text.UTF8Encoding]::new($false))` 或编辑工具重写；
6. **前端 axios 实例必查宿主全局污染**：宿主若在模块级设置了 `axios.defaults.headers`（RuoYi `request.js` 写死全局 `Content-Type: application/json`），`axios.create()` 会继承 → FormData 分片被标成 JSON → 后端报 `Required request parameter 'fileHash' ... is not present`，而 JSON 接口（check/merge）完全正常，极具迷惑性。实例创建后立即 `delete instance.defaults.headers['Content-Type']`，拦截器里对 FormData 兜底删除；
7. 鉴权体系下**下载不能裸用 `<a href>`**（不带 token → 401）：走 axios `responseType:'blob'` + `a[download]` 触发保存。

**构建/运行**：Maven 多模块打包用 `-pl 模块 -am`；Windows 重打包前先停占用 jar 的 java 进程；宿主有验证码时先关（库配置+清缓存）再跑 curl 验证协议。

## 硬规则

- 禁止修改协议字段（`fileHash/chunkIndex/totalChunks/totalSize`）与错误码语义；前端事件状态机（waiting→hashing→checking→uploading→merging→done/error）同样不可改；
- 宿主主类必须补 `@ConfigurationPropertiesScan` + `@EnableScheduling`；
- 多用户系统必须完成 5.3 鉴权接线；**租户来源必须对接宿主已有体系**（实现 `TenantResolver`，默认读请求头的 `HeaderTenantResolver` 只是无租户体系的回落方案），并向用户转达手册 5.4 的边界警告（租户域内秒传探测面等）；
- 前端：MD5 必须留在 Worker（主线程算会卡死大文件页面）；`taskStore` 的 localStorage 键与 IndexedDB 结构是刷新恢复契约，不可改；不要删 `peekProgress`/`markInterrupted`（刷新恢复与"分片已齐不自动合并"语义靠它们）；
- 组件源码以手册中锚定的 commit 为准，禁止凭记忆手写组件内部实现；前端只消费协议，发现"后端行为与手册不符"时修后端，不改协议凑合。

## 故障速查

配置不生效 → 缺 `@ConfigurationPropertiesScan`；孤儿分片不清 → 缺 `@EnableScheduling`；413 → multipart 上限小于单片；S3 报"非末片分片至少 5242880" → 前端分片调大到 5MiB；Windows 路径用正斜杠；前端断点失效 → 检查 taskStore 是否被改动。

实战补充：**分片报 `fileHash ... is not present` 但 check/merge 正常** → 宿主全局 axios defaults 污染 Content-Type（见适配检查 §6）；**编译报 `﻿`** → 文件带 BOM（§5）；**`ConflictingBeanDefinitionException`** → 组件与宿主同名 Bean，组件侧改名（§4）；**`UploadFileRecordMapper` bean 找不到** → 宿主 MapperScan 未覆盖组件包（§3）；**启动时 JDBC/MyBatis 自动装配被排除** → 误复制了 NoDataSourceGuard（§2）。完整表见手册 §7。
