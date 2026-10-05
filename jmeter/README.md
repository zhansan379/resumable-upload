# JMeter 测试计划使用说明

针对 `backend` 上传服务的自动化测试计划（JMeter 5.6.3 验证通过，172 样本 0 错误）。

## 前置准备

```bash
# 1. 启动后端（默认 8080）
cd backend && mvn spring-boot:run

# 2. 生成分片文件（若 testdata/jmeter/ 下没有 chunk_0.bin..chunk_19.bin）
node tmp/split-chunks.mjs    # 把 testdata/test-100MB.bin 拆成 20 × 5MB
```

## 运行

```bash
# 命令行模式（结果写入 jmeter/results/upload-test.jtl）
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -n \
  -t jmeter/resumable-upload-test.jmx \
  -l jmeter/results/upload-test.jtl

# 图形界面模式（查看断言详情）
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -t jmeter/resumable-upload-test.jmx
```

常用参数覆盖（`-J` 传参）：

| 参数 | 默认 | 说明 |
|------|------|------|
| `-Jhost` / `-Jport` | localhost / 8080 | 后端地址 |
| `-JchunkDir` | `<项目>/testdata/jmeter` | 分片文件目录 |
| `-JloadThreads` | 5 | 并发负载组的线程数（每线程每轮上传一个独立 100MB 文件） |
| `-JloadLoops` | 1 | 负载组迭代次数 |
| `-JloadRamp` | 2 | 负载组加速时间（秒） |

例：10 线程、各跑 2 轮的并发压测（总上传 2GB）：

```bash
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -n -t jmeter/resumable-upload-test.jmx \
  -l jmeter/results/load-10t.jtl -JloadThreads=10 -JloadLoops=2
```

## 测试场景

线程组按顺序执行（`serialize_threadgroups=true`）：

| 组 | 场景 | 关键断言 |
|----|------|----------|
| 02-完整上传 | 清理残留 → check → 20 片顺序上传 → merge（真实 MD5 全链路，服务端合并后校验会通过） | `$.finished==false`、`$.url` 非空 |
| 03-秒传验证 | 同 hash 再次 check | `$.finished==true`、`$.url` 非空、**响应时间 < 500ms** |
| 04-下载测试 | 整文件下载 100MB → Range 区间请求 → 超界 Range → If-Range 不匹配 | **200 全量 / 206 + `Content-Range: bytes 0-1048575/104857600` / 416 + `bytes */104857600` / 回退 200 全量** |
| 05-断点续传 | 随机 hash → 传前 10 片 → merge | merge 缺片返回 **409**；check 返回 `uploadedChunks=[0..9]`；补齐后 merge 200；**重复合并幂等 200**；清理分片目录 |
| 06-异常与边界 | 非法 hash（路径穿越）、chunkIndex 越界、缺必填参数、只传 1/3 片后 merge | 分别返回 **400/400/400/409**；取消清理 200；文件列表包含已上传文件 |
| 07-并发负载 | N 线程并发上传独立文件（每线程 20 片 × 5MB）后删除 | 全链路 200，用于吞吐/响应时间统计 |

## HTML 报告

```bash
# 方式一：从已有 .jtl 结果文件生成（-o 目录必须不存在或为空）
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -g jmeter/results/upload-test.jtl -o jmeter/results/report-local

# 方式二：压测结束时自动生成（-e -o 与 -l 连用）
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -n -t jmeter/resumable-upload-test.jmx \
  -l jmeter/results/upload-test.jtl -e -o jmeter/results/report-local

# 中文 Windows 必须加 -Dfile.encoding=UTF-8，否则报告里的中文标签（采样器名）会乱码：
set JVM_ARGS=-Dfile.encoding=UTF-8
D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat -g jmeter/results/upload-test.jtl -o jmeter/results/report-local
```

报告包含：总体统计表（样本数/错误率/均值/中位数/P90/P95/P99/吞吐/Apdex）、响应时间随时间变化曲线、TPS 曲线、错误分布等。
报告通过 AJAX 加载 statistics.json，**直接双击 file:// 打开会被浏览器拦截**，需经 HTTP 访问——项目提供了极简预览服务器：

```bash
node tmp/report-server.mjs    # 然后访问 http://localhost:8899/report-local/ 与 /report-remote/
```

## 说明与注意事项

- 分片内容来自 `test-100MB.bin`（高熵随机数据），其真实 MD5 为
  `9c42e41deba91004c2d523db2eeb1208`，02/03 组使用该真实 hash，因此服务端合并后的异步 MD5 校验会**通过**。
- 04/05/06 组使用**随机伪 hash**（保证可重复运行、不触发秒传）：merge 能成功（大小校验通过），但服务端合并后的 MD5 校验会**记录失败日志**——这是预期行为。重负载压测建议启动后端时加
  `--app.upload.verify-md5-after-merge=false` 关闭该校验。
- 02 组开头会 DELETE 真实 hash 的残留文件，因此整套计划可反复运行。
- 重新生成分片：修改 `tmp/split-chunks.mjs` 中的源文件/分片大小后运行，并同步更新 JMX 中
  `TOTAL_SIZE / TOTAL_CHUNKS / CHUNK_SIZE / REAL_HASH` 变量。
- 结果文件 `jmeter/results/*.jtl` 可导入 JMeter GUI 或用 Aggregate Report 查看吞吐与分位响应时间。
