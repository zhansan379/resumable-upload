import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'

// 对抗性中断测试工具（命令驱动，由 bash 编排在合适时机 docker kill）
// 用法: node scripts/adversarial-test.mjs <upload|check|merge|verify|delete|cancel> [args]
const BASE = (process.env.BASE_URL || 'http://60.205.230.218:8080') + '/api'
const SRC = 'testdata/test-500MB.bin'
const TOTAL_SIZE = 524288000
const CHUNK = 5242880
const TOTAL_CHUNKS = 100
const HASH = 'a8fad976dd20d28c3118ec6f4874f624' // test-500MB.bin 真实 MD5
const NAME = 'adversarial-500MB.bin'

const cmd = process.argv[2]
const arg3 = Number(process.argv[3] ?? 0)
const arg4 = Number(process.argv[4] ?? TOTAL_CHUNKS)
const arg5 = Number(process.argv[5] ?? 6)

async function chunkForm(i) {
  const fh = await fsp.open(SRC, 'r')
  const buf = Buffer.alloc(Math.min(CHUNK, TOTAL_SIZE - i * CHUNK))
  await fh.read(buf, 0, buf.length, i * CHUNK)
  await fh.close()
  const fd = new FormData()
  fd.append('fileHash', HASH)
  fd.append('chunkIndex', String(i))
  fd.append('totalChunks', String(TOTAL_CHUNKS))
  fd.append('chunkSize', String(CHUNK))
  fd.append('totalSize', String(TOTAL_SIZE))
  fd.append('fileName', NAME)
  fd.append('chunk', new Blob([buf]))
  return fd
}
const j = async (r) => ({ status: r.status, body: await r.json().catch(() => null) })

async function upload(from, to, conc) {
  let cursor = from
  let done = 0
  const errs = []
  const t0 = Date.now()
  async function w() {
    while (cursor < to) {
      const i = cursor++
      try {
        const r = await fetch(`${BASE}/upload/chunk`, { method: 'POST', body: await chunkForm(i) })
        if (r.status !== 200) errs.push(`#${i}=${r.status}`)
        else done++
      } catch (e) { errs.push(`#${i}=${e.message}`) }
    }
  }
  await Promise.all(Array.from({ length: conc }, w))
  console.log(JSON.stringify({ cmd: 'upload', from, to, done, errs: errs.slice(0, 5), sec: ((Date.now() - t0) / 1000).toFixed(1) }))
}

async function checkOnce() {
  const r = await j(await fetch(`${BASE}/upload/check`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileHash: HASH, fileName: NAME, totalSize: TOTAL_SIZE, totalChunks: TOTAL_CHUNKS, chunkSize: CHUNK })
  }))
  return r
}

async function main() {
  if (cmd === 'upload') { await upload(arg3, arg4, arg5); return }
  if (cmd === 'check') { console.log(JSON.stringify(await checkOnce())); return }
  if (cmd === 'merge') {
    const t0 = Date.now()
    const r = await j(await fetch(`${BASE}/upload/merge`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileHash: HASH, fileName: NAME, totalSize: TOTAL_SIZE, totalChunks: TOTAL_CHUNKS, chunkSize: CHUNK })
    })).catch(e => ({ status: 0, body: { message: e.message } }))
    console.log(JSON.stringify({ cmd: 'merge', sec: ((Date.now() - t0) / 1000).toFixed(2), ...r }))
    return
  }
  if (cmd === 'verify') {
    // 轮询 check 直到服务端异步 MD5 校验通过（verified=true）；超时则报告最终状态
    const timeout = (arg3 || 60) * 1000
    const t0 = Date.now()
    let last = null
    while (Date.now() - t0 < timeout) {
      const r = await checkOnce()
      last = r.body?.verified
      if (r.status === 200 && r.body.finished && r.body.verified === true) {
        console.log(JSON.stringify({ cmd: 'verify', verified: true, waitedSec: ((Date.now() - t0) / 1000).toFixed(1) }))
        return
      }
      await new Promise(res => setTimeout(res, 2000))
    }
    console.log(JSON.stringify({ cmd: 'verify', verified: last, waitedSec: ((Date.now() - t0) / 1000).toFixed(1) }))
    return
  }
  if (cmd === 'delete') { const r = await j(await fetch(`${BASE}/files/${HASH}`, { method: 'DELETE' })); console.log(JSON.stringify({ cmd: 'delete', ...r })); return }
  if (cmd === 'cancel') { const r = await j(await fetch(`${BASE}/upload/${HASH}`, { method: 'DELETE' })); console.log(JSON.stringify({ cmd: 'cancel', ...r })); return }
  console.error('未知命令:', cmd)
  process.exit(1)
}
main().catch(e => { console.error('脚本异常:', e.message); process.exit(1) })
