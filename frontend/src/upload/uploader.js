import { checkUpload, uploadChunk, mergeChunks, cancelUpload } from '../api'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function isAborted(err) {
  return !!err && (err.code === 'ERR_CANCELED' || err.name === 'CanceledError' || err.name === 'AbortError')
}

/**
 * 分片上传调度器（协议设计来源见 docs/01-开源项目调研分析.md 第五节）：
 * 1. spark-md5 Worker 计算整文件 MD5 作为全局唯一标识（simple-uploader 社区方案）；
 * 2. check 一次取回"已传分片列表"实现断点续传，finished=true 即秒传；
 * 3. 并发上传分片（默认 3），4xx 永久错误直接失败，网络/5xx 错误指数退避重试；
 * 4. 全片完成后调用显式合并端点（fine-uploader success.endpoint 思想）。
 * 暂停/取消通过 AbortController 中断在途请求；服务端磁盘是分片状态的唯一事实源。
 */
export class Uploader {

  constructor(file, { chunkSize, concurrency = 3, retries = 3, retryDelay = 1000, initialHash = null, onEvent } = {}) {
    this.file = file
    this.chunkSize = chunkSize
    this.concurrency = Math.max(1, concurrency | 0)
    this.retries = retries
    this.retryDelay = retryDelay
    this.initialHash = initialHash // 恢复历史任务时复用已计算的 MD5，避免重新计算
    this.onEvent = onEvent || (() => {})

    this.totalChunks = Math.max(1, Math.ceil(file.size / this.chunkSize))
    this.fileHash = null
    this.uploadedChunks = new Set()
    this.inflight = new Map() // chunkIndex -> { controller, loaded }
    this.state = 'idle'
    this.paused = false
    this.cancelled = false
    this.runId = 0
    this.errorMessage = ''

    // 弱网自适应并发（AIMD）：分片重试耗尽的传输类失败 → 并发减半（下限 1）；
    // 连续成功 10 片 → 并发 +1 爬回用户设定值。只动调度，不动分片大小（分片大小是任务身份，中途改变会作废断点进度）
    this.userConcurrency = this.concurrency
    this.effectiveConcurrency = this.concurrency
    this.adaptRounds = 0
    this.maxAdaptRounds = 6
    this.successStreak = 0
    this.requeue = []
    this.lastChunkError = null

    this.speed = 0
    this.lastEmitAt = 0
    this.lastTickAt = 0
    this.lastTickBytes = 0
  }

  emit(type, payload = {}) {
    this.onEvent({ type, ...payload })
  }

  chunkSizeOf(index) {
    const start = index * this.chunkSize
    return Math.min(this.file.size, start + this.chunkSize) - start
  }

  uploadedBytes() {
    let bytes = 0
    for (const i of this.uploadedChunks) bytes += this.chunkSizeOf(i)
    return bytes
  }

  inflightBytes() {
    let bytes = 0
    for (const item of this.inflight.values()) bytes += item.loaded || 0
    return bytes
  }

  get progress() {
    if (!this.file.size) return 100
    return Math.min(99.9, ((this.uploadedBytes() + this.inflightBytes()) / this.file.size) * 100)
  }

  /* ---------------- 对外动作 ---------------- */

  /** 开始：计算 MD5 -> check -> 调度上传 -> 合并 */
  async start() {
    if (this.initialHash) {
      // 恢复的历史任务：MD5 已算过，直接进 check
      this.fileHash = this.initialHash
      this.emit('hash', { fileHash: this.fileHash })
      if (this.cancelled) return
      await this.checkAndRun(this.runId + 1)
      return
    }
    this.state = 'hashing'
    this.emit('state')
    try {
      this.fileHash = await this.computeHash()
    } catch (err) {
      if (this.cancelled) return
      return this.fail(new Error('MD5 计算失败：' + err.message))
    }
    this.emit('hash', { fileHash: this.fileHash })
    if (this.cancelled) return
    await this.checkAndRun(this.runId + 1)
  }

  pause() {
    if (this.state !== 'uploading' && this.state !== 'checking') return
    this.paused = true
    this.runId++
    for (const item of this.inflight.values()) item.controller.abort()
    this.state = 'paused'
    this.emit('state')
  }

  /** 继续：重新 check 同步服务端分片状态后再调度（服务端磁盘为事实源） */
  async resume() {
    if (this.state !== 'paused' || this.cancelled) return
    this.paused = false
    await this.checkAndRun(this.runId + 1)
  }

  /** 失败重试：与继续相同，重走 check 修正本地分片集合 */
  async retry() {
    if (this.state !== 'error' || this.cancelled) return
    this.paused = false
    await this.checkAndRun(this.runId + 1)
  }

  /** 恢复历史任务时标记为暂停态，等待用户点"继续" */
  markInterrupted() {
    if (this.state === 'idle') {
      this.paused = true
      this.state = 'paused'
    }
  }

  /** 静默同步服务端实际进度（不启动上传、不改变状态）：恢复已暂停/失败任务时让进度条直接对齐。
   *  返回 { autoMerge }：已暂停的任务即使分片已齐也不应自动合并（需用户点继续），由调用方屏蔽自动续传。 */
  async peekProgress() {
    const hash = this.fileHash || this.initialHash
    if (!hash) return { autoMerge: false }
    this.fileHash = hash
    try {
      const res = await checkUpload({
        fileHash: hash,
        fileName: this.file.name,
        totalSize: this.file.size,
        totalChunks: this.totalChunks,
        chunkSize: this.chunkSize
      })
      if (res.finished) {
        this.uploadedChunks = new Set(Array.from({ length: this.totalChunks }, (_, i) => i))
      } else {
        this.uploadedChunks = new Set(res.uploadedChunks || [])
      }
      this.emitProgress(true)
      return { autoMerge: !res.finished && this.uploadedChunks.size < this.totalChunks }
    } catch {
      return { autoMerge: false }
    }
  }

  cancel() {
    if (this.cancelled) return
    this.cancelled = true
    this.paused = true
    this.runId++
    for (const item of this.inflight.values()) item.controller.abort()
    if (this.fileHash) {
      cancelUpload(this.fileHash).catch(() => {})
    }
  }

  /* ---------------- 内部流程 ---------------- */

  /** 弱网信号：重试耗尽的传输类失败 → 并发减半（下限 1），并递增自适应轮数 */
  adaptDown() {
    this.adaptRounds++
    if (this.effectiveConcurrency > 1) {
      this.effectiveConcurrency = Math.max(1, Math.floor(this.effectiveConcurrency / 2))
      this.successStreak = 0
      this.emit('concurrency', { value: this.effectiveConcurrency, user: this.userConcurrency })
    }
  }

  /** 分片成功：连续成功 10 片后并发 +1，逐步爬回用户设定值 */
  noteSuccess() {
    this.successStreak++
    if (this.effectiveConcurrency < this.userConcurrency && this.successStreak >= 10) {
      this.effectiveConcurrency++
      this.successStreak = 0
      this.emit('concurrency', { value: this.effectiveConcurrency, user: this.userConcurrency })
    }
  }

  async checkAndRun(rid) {
    // 恢复的历史任务（markInterrupted 路径）没经过 start()，在此统一补上持久化的 MD5，
    // 否则 resume()/retry() 会带着空 fileHash 去 check（后端 400 fileHash 非法）
    if (!this.fileHash && this.initialHash) {
      this.fileHash = this.initialHash
      this.emit('hash', { fileHash: this.fileHash })
    }
    this.runId = rid
    try {
      this.state = 'checking'
      this.emit('state')
      const res = await checkUpload({
        fileHash: this.fileHash,
        fileName: this.file.name,
        totalSize: this.file.size,
        totalChunks: this.totalChunks,
        chunkSize: this.chunkSize
      })
      if (this.runId !== rid || this.cancelled || this.paused) return

      if (res.finished) {
        // 秒传：服务端已有相同 hash 的完整文件
        this.uploadedChunks = new Set(Array.from({ length: this.totalChunks }, (_, i) => i))
        this.state = 'done'
        this.emit('state')
        this.emit('done', { url: res.url, instant: true, verified: res.verified })
        return
      }

      this.uploadedChunks = new Set(res.uploadedChunks || [])
      // 立即按服务端实际进度刷新一次展示（恢复任务/续传时进度条直接对齐服务端状态）
      this.emitProgress(true)
      if (this.uploadedChunks.size >= this.totalChunks) {
        // 分片已齐但尚未合并（上次合并请求中断），直接补一次合并
        await this.runMerge(rid)
        return
      }

      this.state = 'uploading'
      this.lastTickAt = 0
      this.emit('state')
      await this.schedule(rid)
      if (this.runId !== rid || this.cancelled || this.paused) return
      await this.runMerge(rid)
    } catch (err) {
      if (this.runId !== rid || this.cancelled) return
      if (this.paused || isAborted(err)) {
        this.state = 'paused'
        this.emit('state')
        return
      }
      this.fail(err)
    }
  }

  async runMerge(rid) {
    this.state = 'merging'
    this.emit('state')
    const res = await mergeChunks({
      fileHash: this.fileHash,
      fileName: this.file.name,
      totalSize: this.file.size,
      totalChunks: this.totalChunks,
      chunkSize: this.chunkSize
    })
    if (this.runId !== rid || this.cancelled) return
    this.state = 'done'
    this.emit('state')
    this.emit('done', { url: res.url, instant: false, verified: res.verified })
  }

  async schedule(rid) {
    this.adaptRounds = 0
    this.requeue = []
    const queue = []
    for (let i = 0; i < this.totalChunks; i++) {
      if (!this.uploadedChunks.has(i)) queue.push(i)
    }
    const workers = Math.min(this.userConcurrency, queue.length)
    await Promise.all(Array.from({ length: workers }, () => this.workerLoop(queue, rid)))
    // 弱网自适应：workerLoop 把重试耗尽的传输失败片回队并降低并发，按新并发继续调度，
    // 直到全部完成或达到自适应轮数上限（此后沿用整体失败语义，等待用户手动重试）
    while (this.requeue.length && this.adaptRounds <= this.maxAdaptRounds && this.runId === rid && !this.paused && !this.cancelled) {
      const requeued = this.requeue.splice(0)
      for (const i of requeued) {
        if (!this.uploadedChunks.has(i)) queue.push(i)
      }
      if (!queue.length) break
      const workers = Math.min(Math.max(this.effectiveConcurrency, 1), queue.length)
      await Promise.all(Array.from({ length: workers }, () => this.workerLoop(queue, rid)))
    }
    const remain = queue.length + this.requeue.length
    if (remain > 0 && this.runId === rid && !this.paused && !this.cancelled) {
      throw this.lastChunkError || new Error(`分片上传失败：${remain} 片未完成`)
    }
  }

  async workerLoop(queue, rid) {
    while (this.runId === rid && !this.paused && !this.cancelled) {
      // 并发闸门：在途分片达到当前自适应并发时不取新任务（降并发后多余的 worker 自然闲置）
      if (this.inflight.size >= this.effectiveConcurrency) {
        await sleep(120)
        continue
      }
      const index = queue.shift()
      if (index === undefined) return
      await this.uploadChunkWithRetry(index)
    }
  }

  async uploadChunkWithRetry(index) {
    const size = this.chunkSizeOf(index)
    const blob = this.file.slice(index * this.chunkSize, index * this.chunkSize + size)
    const controller = new AbortController()
    const item = { controller, loaded: 0 }
    this.inflight.set(index, item)
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          const fd = new FormData()
          fd.append('fileHash', this.fileHash)
          fd.append('chunkIndex', index)
          fd.append('totalChunks', this.totalChunks)
          fd.append('chunkSize', this.chunkSize)
          fd.append('totalSize', this.file.size)
          fd.append('fileName', this.file.name)
          fd.append('chunk', blob, `chunk-${index}`)
          await uploadChunk(fd, {
            signal: controller.signal,
            onProgress: (e) => {
              item.loaded = e.loaded || 0
              this.emitProgress()
            }
          })
          this.uploadedChunks.add(index)
          item.loaded = size
          this.noteSuccess()
          this.emitProgress(true)
          return
        } catch (err) {
          if (controller.signal.aborted) throw err // 暂停/取消导致的中断，不重试
          const status = err?.response?.status
          // 4xx（除 429）为永久错误，重试无意义，直接失败
          const permanent = status && status >= 400 && status < 500 && status !== 429
          if (!permanent) this.successStreak = 0
          if (permanent || attempt >= this.retries) {
            // 传输类失败且重试耗尽 = 弱网信号：降低并发并把该片放回队列，
            // 由 schedule 按新并发再调度；不超过自适应轮数上限
            if (!permanent && this.adaptRounds < this.maxAdaptRounds) {
              this.adaptDown()
              this.lastChunkError = err
              this.requeue.push(index)
              return
            }
            throw err
          }
          await sleep(this.retryDelay * (attempt + 1))
          if (controller.signal.aborted) throw err
        }
      }
    } finally {
      this.inflight.delete(index)
    }
  }

  emitProgress(force = false) {
    const now = performance.now()
    if (!force && now - this.lastEmitAt < 120) return
    this.lastEmitAt = now
    const bytes = this.uploadedBytes() + this.inflightBytes()
    if (this.lastTickAt) {
      const dt = (now - this.lastTickAt) / 1000
      if (dt > 0.2) {
        const instant = Math.max(0, (bytes - this.lastTickBytes) / dt)
        this.speed = this.speed ? this.speed * 0.6 + instant * 0.4 : instant
        this.lastTickAt = now
        this.lastTickBytes = bytes
      }
    } else {
      this.lastTickAt = now
      this.lastTickBytes = bytes
    }
    this.emit('progress', {
      percent: this.progress,
      speed: this.speed,
      done: this.uploadedChunks.size,
      total: this.totalChunks
    })
  }

  /** spark-md5 Worker：2MB 分片增量计算，不整体载入内存 */
  computeHash() {
    return new Promise((resolve, reject) => {
      const worker = new Worker(new URL('./md5Worker.js', import.meta.url), { type: 'module' })
      worker.onmessage = (e) => {
        const { type, percent, md5, message } = e.data || {}
        if (type === 'progress') {
          this.emit('hash-progress', { percent })
        } else if (type === 'done') {
          worker.terminate()
          resolve(md5)
        } else if (type === 'error') {
          worker.terminate()
          reject(new Error(message || 'worker error'))
        }
      }
      worker.onerror = (e) => {
        worker.terminate()
        reject(new Error(e.message || 'MD5 worker 启动失败'))
      }
      worker.postMessage({ file: this.file })
    })
  }

  fail(err) {
    this.errorMessage = err?.response?.data?.message || err?.message || '上传失败'
    this.state = 'error'
    this.emit('state')
    this.emit('error', { message: this.errorMessage })
  }
}
