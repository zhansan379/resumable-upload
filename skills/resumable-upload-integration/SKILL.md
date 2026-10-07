---
name: resumable-upload-integration
description: 将 resumable-upload 大文件上传组件（分片上传/断点续传/秒传/可插拔存储）集成到用户的 Spring Boot 项目。当用户要求"集成大文件上传、分片上传、断点续传、秒传、resumable upload 组件"时使用。
---

# resumable-upload 组件集成 Skill

把 [zhansan379/resumable-upload](https://github.com/zhansan379/resumable-upload) 组件以**复制源码**的方式集成进宿主 Spring Boot 项目。完整手册在仓库的 `docs/07-组件集成指南.md`——先拿到它，再严格照做。

## 执行流程

1. **拿手册**（任选其一）：
   ```bash
   curl -sL https://raw.githubusercontent.com/zhansan379/resumable-upload/main/docs/07-组件集成指南.md
   ```
   或从用户提供的仓库副本直接读。
2. **前置判断**：宿主必须是 Spring Boot 3.x + Java 17。不满足 → 停止，向用户说明原因，不做降级尝试。
3. **照手册执行**：第 2 节文件清单复制（注意⛔标记的演示壳文件不复制）→ 第 3/4 节步骤 → 第 5 节适配点。
4. **必答题（动手前问用户或读宿主代码确认）**：API 前缀是否需要改、宿主的鉴权体系怎么接、存储用 local 还是 S3（S3 要拿到 endpoint/凭证/桶名）。
5. **验证**：执行手册第 6 节的 curl 验证协议，**逐项汇报结果**。任何一步不符，查第 7 节故障表，修完重跑全链，不许跳过验证宣布完成。

## 硬规则

- 禁止修改协议字段（`fileHash/chunkIndex/totalChunks/totalSize`）与错误码语义；
- 宿主主类必须补 `@ConfigurationPropertiesScan` + `@EnableScheduling`——这是最高频的集成失败原因；
- 多用户系统必须完成 5.3 鉴权接线，并向用户转达 5.4 的租户边界警告（fileHash 全局索引、秒传探测面）；
- 组件源码以手册中锚定的 commit 为准，禁止凭记忆手写组件内部实现。

## 常见故障速查

配置不生效→缺 `@ConfigurationPropertiesScan`；413→multipart 上限；S3 报"非末片分片至少 5242880"→前端单片调大到 5MiB；Windows 路径用正斜杠。完整表见手册第 7 节。
