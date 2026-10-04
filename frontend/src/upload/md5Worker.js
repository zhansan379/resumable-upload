import SparkMD5 from 'spark-md5'

/**
 * 文件 MD5 计算Worker：
 * 按 2MB 分片增量计算整文件 MD5（spark-md5），避免一次性读入整个大文件撑爆内存。
 * 通过 postMessage 传入 File/Blob（结构化克隆），计算进度实时回传。
 */
self.onmessage = async (e) => {
  const { file, hashChunkSize = 2 * 1024 * 1024 } = e.data
  try {
    const spark = new SparkMD5.ArrayBuffer()
    const total = Math.max(1, Math.ceil(file.size / hashChunkSize))
    for (let i = 0; i < total; i++) {
      if (i % 4 === 0) {
        // 每 4 片让出一次事件循环，保持 worker 响应性
        await new Promise((r) => setTimeout(r, 0))
      }
      const start = i * hashChunkSize
      const buf = await file.slice(start, Math.min(file.size, start + hashChunkSize)).arrayBuffer()
      spark.append(buf)
      self.postMessage({ type: 'progress', percent: Math.round(((i + 1) / total) * 100) })
    }
    self.postMessage({ type: 'done', md5: spark.end() })
  } catch (err) {
    self.postMessage({ type: 'error', message: String(err && err.message ? err.message : err) })
  }
}
