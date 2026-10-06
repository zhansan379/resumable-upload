import fsp from 'node:fs/promises'

// 弱网注入上传测试（双模式）：
//   chunks    —— 分片上传，前端语义：3 并发、每片失败按 1s/2s/3s 退避重试 3 次、失败片修复轮
//   wholefile —— 传统整文件语义：单流顺序上传、无分片重试，任一请求失败则整个文件从头再来
//   chaos     —— 乱序 + 重复分片（幂等性验证）
// 用法: node scripts/weaknet-test.mjs <chunks|wholefile|chaos> <label>
const BASE = (process.env.BASE_URL || 'http://60.205.230.218:8080') + '/api'
const SRC = 'testdata/test-100MB.bin'
const TOTAL = 104857600
const CHUNK = 5242880
const N = 20
const HASH = '9c42e41deba91004c2d523db2eeb1208' // test-100MB.bin 真实 MD5
const NAME = 'weaknet-100MB.bin'
const mode = process.argv[2] || 'chunks'
const label = process.argv[3] || mode
const CONC = Number(process.argv[4]) || 3
const DEADLINE = Date.now() + 480000 // 单场景 4 分钟上限

const sleep = ms => new Promise(r => setTimeout(r, ms))
const stats = { label, mode, attempts: 0, bytesSentMB: 0, chunkFails: 0, restarts: 0, merged: false, verified: null, ok: false, wallSec: 0 }
const t0 = Date.now()

async function chunkForm(i) {
  const fh = await fsp.open(SRC, 'r')
  const buf = Buffer.alloc(Math.min(CHUNK, TOTAL - i * CHUNK))
  await fh.read(buf, 0, buf.length, i * CHUNK)
  await fh.close()
  const fd = new FormData()
  fd.append('fileHash', HASH)
  fd.append('chunkIndex', String(i))
  fd.append('totalChunks', String(N))
  fd.append('chunkSize', String(CHUNK))
  fd.append('totalSize', String(TOTAL))
  fd.append('fileName', NAME)
  fd.append('chunk', new Blob([buf]))
  return fd
}

async function put(i) {
  stats.attempts++
  stats.bytesSentMB += 5
  const r = await fetch(`${BASE}/upload/chunk`, {
    method: 'POST', body: await chunkForm(i), signal: AbortSignal.timeout(60000)
  })
  if (r.status !== 200) throw new Error('http ' + r.status)
}

/** 前端语义重试：初始 + 3 次退避（1s/2s/3s） */
async function putWithRetry(i) {
  for (const w of [0, 1000, 2000, 3000]) {
    if (Date.now() > DEADLINE) return false
    if (w) await sleep(w)
    try { await put(i); return true } catch { /* 重试 */ }
  }
  return false
}

async function merge() {
  const r = await fetch(`${BASE}/upload/merge`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileHash: HASH, fileName: NAME, totalSize: TOTAL, totalChunks: N, chunkSize: CHUNK }),
    signal: AbortSignal.timeout(60000)
  })
  return r.status === 200
}

/** merge 也需要重试容错：断连场景下 merge 的响应可能被杀（服务端可能已成功） */
async function mergeWithRetry() {
  for (let k = 0; k < 5; k++) {
    try { if (await merge()) return true } catch { /* 重试 */ }
    await sleep(1000)
  }
  return false
}

async function verify(timeoutMs = 90000) {
  const t = Date.now()
  while (Date.now() - t < timeoutMs && Date.now() < DEADLINE + 60000) {
    try {
      const r = await fetch(`${BASE}/upload/check`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileHash: HASH, fileName: NAME, totalSize: TOTAL, totalChunks: N, chunkSize: CHUNK })
      })
      const b = await r.json()
      if (b.finished && b.verified === true) { stats.verified = true; return true }
    } catch { /* 轮询容错 */ }
    await sleep(2000)
  }
  stats.verified = false
  return false
}

async function chunksMode() {
  const failed = new Set()
  let cursor = 0
  async function worker() {
    while (cursor < N) {
      if (Date.now() > DEADLINE) return
      const i = cursor++
      if (!(await putWithRetry(i))) failed.add(i)
    }
  }
  await Promise.all(Array.from({ length: CONC }, worker))
  // 修复轮：重试失败片（模拟用户点"继续"）
  for (let round = 0; round < 10 && failed.size && Date.now() < DEADLINE; round++) {
    for (const i of [...failed]) {
      if (await putWithRetry(i)) failed.delete(i)
    }
  }
  if (failed.size) return // 片不齐，merge 必 409
  stats.merged = await mergeWithRetry()
  while (!stats.merged && Date.now() < DEADLINE) {
    // merge 失败(缺片/5xx) → 补传全部片再试
    for (let i = 0; i < N && Date.now() < DEADLINE; i++) await putWithRetry(i)
    stats.merged = await mergeWithRetry()
  }
}

async function wholefileMode() {
  // 传统整文件：顺序单流、无重试；任一请求失败 → 整个文件从第 0 片重来
  for (let pass = 1; pass <= 4; pass++) {
    if (pass > 1) stats.restarts++
    if (Date.now() > DEADLINE) return
    let broken = false
    for (let i = 0; i < N; i++) {
      try { await put(i) } catch { broken = true; stats.chunkFails++; break }
    }
    if (broken) continue
    stats.merged = await mergeWithRetry()
    if (stats.merged) break
    stats.chunkFails++
  }
}

async function chaosMode() {
  // 乱序 + 重复分片：顺序无关性 + 覆盖写幂等性
  const order = [...Array(N).keys()]
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[order[i], order[j]] = [order[j], order[i]]
  }
  const dup = new Set(order.slice(0, 3)) // 3 个分片上传两遍
  const failed = new Set()
  let cursor = 0
  async function worker() {
    while (cursor < order.length) {
      if (Date.now() > DEADLINE) return
      const i = order[cursor++]
      if (!(await putWithRetry(i))) failed.add(i)
      if (dup.has(i)) { try { await put(i) } catch { /* 重复片失败不影响主流程 */ } }
    }
  }
  await Promise.all(Array.from({ length: CONC }, worker))
  for (let round = 0; round < 10 && failed.size && Date.now() < DEADLINE; round++) {
    for (const i of [...failed]) if (await putWithRetry(i)) failed.delete(i)
  }
  if (failed.size) return
  stats.merged = await mergeWithRetry()
}

async function main() {
  if (mode === 'chunks') await chunksMode()
  else if (mode === 'wholefile') await wholefileMode()
  else if (mode === 'chaos') await chaosMode()
  if (stats.merged) await verify()
  stats.ok = stats.merged && stats.verified === true
  stats.wallSec = +((Date.now() - t0) / 1000).toFixed(1)
  stats.bytesSentMB = +stats.bytesSentMB.toFixed(0)
  console.log(JSON.stringify(stats))
}

main().catch(e => { stats.wallSec = +((Date.now() - t0) / 1000).toFixed(1); console.log(JSON.stringify({ ...stats, ok: false, error: e.message })) })
