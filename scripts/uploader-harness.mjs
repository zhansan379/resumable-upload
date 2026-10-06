import axios from '../frontend/node_modules/axios/index.js'
import fs from 'node:fs'

// 前端 Uploader 真实代码的弱网测试台：
// 1. 覆写 axios 默认 adapter，把 api.js 的相对地址桥接到目标服务器；
// 2. 动态 import 前端 uploader.js（此时实例才创建，会继承覆写后的 adapter）；
// 3. 用 initialHash 跳过 MD5 Worker（浏览器专属），其余走真实上传器代码路径。
const TARGET = process.env.TARGET || 'http://60.205.230.218:8080'
const HASH = '9c42e41deba91004c2d523db2eeb1208' // test-100MB.bin 真实 MD5
const CONC = Number(process.env.CONC || 3)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const prevAdapter = axios.defaults.adapter
const httpAdapter = axios.getAdapter(prevAdapter) // defaults.adapter 是适配器名数组，解析为函数
axios.defaults.adapter = async (config) => {
  // api.js 的 baseURL '/api' 由 http adapter 内部拼接，此处 config.url 还不含 /api 前缀
  if (config.url && config.url.startsWith('/')) config.url = TARGET + '/api' + config.url
  config.baseURL = undefined
  return httpAdapter(config)
}

const { Uploader } = await import('../frontend/src/upload/uploader.js')

const buf = fs.readFileSync('testdata/test-100MB.bin')
const file = new File([buf], 'weaknet-aim.bin')

const concEvents = []
let taskError = null
const timeline = []
const up = new Uploader(file, {
  chunkSize: 5 * 1024 * 1024,
  concurrency: CONC,
  retries: 3,
  retryDelay: 1000,
  initialHash: HASH,
  onEvent: (e) => {
    timeline.push({ ts: +(Date.now() - t0 / 1).toFixed(0), tsMs: Date.now() - t0, type: e.type, ...e })
    if (e.type === 'concurrency') concEvents.push(e.value)
    if (e.type === 'error') taskError = e.message
  }
})

const t0 = Date.now()
await up.start().catch(() => {})
// 模拟用户点"重试"：check/早期失败时重试进入上传阶段
let guard = 0
while (up.state === 'error' && up.uploadedChunks.size === 0 && guard < 6) {
  await sleep(2000)
  await up.retry().catch(() => {})
  guard++
}
// 等待任务收敛；中途 error 也模拟点"重试"（自适应并发会在任务实例上保持）
while (up.state !== 'done' && Date.now() - t0 < 420000) {
  await sleep(500)
  if (up.state === 'error' && guard < 12) {
    await sleep(1500)
    await up.retry().catch(() => {})
    guard++
  }
}
console.log(JSON.stringify({
  state: up.state,
  ok: up.state === 'done',
  concEvents,
  effectiveConcurrency: up.effectiveConcurrency,
  doneChunks: up.uploadedChunks.size,
  totalChunks: up.totalChunks,
  wallSec: +((Date.now() - t0) / 1000).toFixed(1),
  error: taskError,
  timeline: timeline.slice(0, 6)
}))
