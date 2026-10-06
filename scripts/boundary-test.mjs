import crypto from 'node:crypto'
import fs from 'node:fs'

// 边界文件测试：0字节 / 恰好1个分片 / 恰好达到大小上限 / 1000个1MB小文件并发（合并风暴）
// 用法: BASE_URL=http://<host>:8080 node scripts/boundary-test.mjs
const BASE = (process.env.BASE_URL || 'http://60.205.230.218:8080') + '/api'
const OUT = 'jmeter/results/boundary'
fs.mkdirSync(OUT, { recursive: true })
const IDX = `${OUT}/boundary-index.csv`
fs.writeFileSync(IDX, '')
const mark = (type, note) => fs.appendFileSync(IDX, `${Date.now()},${type},${note}\n`)

let ok = 0
let fail = 0
const failures = []
function expect(cond, name, extra = '') {
  if (cond) { ok++; console.log('PASS', name) }
  else { fail++; failures.push({ name, extra }); console.log('FAIL', name, extra) }
}
const j = async (res) => ({ status: res.status, body: await res.json().catch(() => null) })
const req = (url, opts = {}) => fetch(url, { signal: AbortSignal.timeout(60000), ...opts })
const jsonReq = (url, obj, method = 'POST') => req(url, {
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj)
})
function chunkForm(hash, index, totalChunks, chunkSize, totalSize, buf, fileName) {
  const fd = new FormData()
  fd.append('fileHash', hash)
  fd.append('chunkIndex', String(index))
  fd.append('totalChunks', String(totalChunks))
  fd.append('chunkSize', String(chunkSize))
  fd.append('totalSize', String(totalSize))
  fd.append('fileName', fileName)
  fd.append('chunk', new Blob([buf]), `chunk-${index}`)
  return fd
}
const randHash = () => crypto.randomUUID().replace(/-/g, '')

async function main() {
  // 0. 就绪探测 + 基线文件数
  for (let i = 0; i < 30; i++) {
    try { await req(`${BASE}/files`); break } catch { await new Promise(r => setTimeout(r, 1000)) }
  }
  const baseline = await j(await req(`${BASE}/files`))
  const baselineCount = baseline.body.length
  console.log(`基线文件数: ${baselineCount}`)

  /* ---------- A. 0字节文件 ---------- */
  mark('START', 'A-zero-byte')
  {
    const hash = randHash()
    let r = await j(await jsonReq(`${BASE}/upload/check`, { fileHash: hash, fileName: 'zero.bin', totalSize: 0, totalChunks: 1, chunkSize: 5242880 }))
    expect(r.status === 200 && r.body.finished === false, 'A1 check(totalSize=0) 正常受理不崩溃', JSON.stringify(r))
    r = await j(await req(`${BASE}/upload/chunk`, { method: 'POST', body: chunkForm(hash, 0, 1, 5242880, 0, Buffer.alloc(0), 'zero.bin') }))
    expect(r.status === 400, 'A2 0字节分片被拒 400', JSON.stringify(r))
    r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'zero.bin', totalSize: 0, totalChunks: 1, chunkSize: 5242880 }))
    expect(r.status === 400, 'A3 merge(totalSize=0) 被拒 400', JSON.stringify(r))
    r = await j(await jsonReq(`${BASE}/upload/check`, { fileHash: hash, fileName: 'zero.bin', totalSize: 0, totalChunks: 1, chunkSize: 5242880 }))
    expect(r.status === 200 && r.body.uploadedChunks.length === 0, 'A4 拒绝后无残留分片', JSON.stringify(r.body))
  }
  mark('END', 'A-zero-byte')

  /* ---------- B. 恰好 1 个分片（5MB） ---------- */
  mark('START', 'B-exact-1-chunk')
  {
    const buf = Buffer.alloc(5 * 1024 * 1024)
    crypto.randomFillSync(buf)
    const hash = crypto.createHash('md5').update(buf).digest('hex')
    let r = await j(await jsonReq(`${BASE}/upload/check`, { fileHash: hash, fileName: 'exact-1-chunk.bin', totalSize: buf.length, totalChunks: 1, chunkSize: buf.length }))
    expect(r.status === 200 && r.body.finished === false, 'B1 check 未传', JSON.stringify(r.body))
    r = await j(await req(`${BASE}/upload/chunk`, { method: 'POST', body: chunkForm(hash, 0, 1, buf.length, buf.length, buf, 'exact-1-chunk.bin') }))
    expect(r.status === 200 && r.body.size === buf.length, 'B2 恰好1个分片上传', JSON.stringify(r.body))
    r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'exact-1-chunk.bin', totalSize: buf.length, totalChunks: 1, chunkSize: buf.length }))
    expect(r.status === 200 && r.body.size === buf.length, 'B3 合并成功', JSON.stringify(r.body))
    r = await j(await jsonReq(`${BASE}/upload/check`, { fileHash: hash, fileName: 'exact-1-chunk.bin', totalSize: buf.length, totalChunks: 1, chunkSize: buf.length }))
    expect(r.body.finished === true, 'B4 秒传命中', JSON.stringify(r.body))
    const dl = await req(new URL(r.body.url, BASE))
    const got = Buffer.from(await dl.arrayBuffer())
    expect(got.length === buf.length && crypto.createHash('md5').update(got).digest('hex') === hash, 'B5 下载内容一致')
    r = await j(await req(`${BASE}/files/${hash}`, { method: 'DELETE' }))
    expect(r.status === 200, 'B6 清理', JSON.stringify(r.body))
  }
  mark('END', 'B-exact-1-chunk')

  /* ---------- C. 大小上限 ---------- */
  mark('START', 'C-chunk-at-limit')
  {
    // C1 恰好 20MB（multipart 单片上限 20MB，含边界）
    const buf = Buffer.alloc(20 * 1024 * 1024)
    crypto.randomFillSync(buf)
    const hash = crypto.createHash('md5').update(buf).digest('hex')
    let r = await j(await req(`${BASE}/upload/chunk`, { method: 'POST', body: chunkForm(hash, 0, 1, buf.length, buf.length, buf, 'at-limit.bin') }))
    expect(r.status === 200 && r.body.size === buf.length, 'C1 恰好20MB单片被接受', JSON.stringify(r))
    r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'at-limit.bin', totalSize: buf.length, totalChunks: 1, chunkSize: buf.length }))
    expect(r.status === 200 && r.body.size === buf.length, 'C1b 恰好20MB文件合并成功', JSON.stringify(r))
    await req(`${BASE}/files/${hash}`, { method: 'DELETE' })
  }
  mark('END', 'C-chunk-at-limit')

  mark('START', 'C-chunk-over-limit')
  {
    // C2 超限 20MB+1 → 413（MaxUploadSizeExceededException 映射）
    const hash = randHash()
    const big = Buffer.alloc(20 * 1024 * 1024 + 1)
    crypto.randomFillSync(big)
    const r = await j(await req(`${BASE}/upload/chunk`, { method: 'POST', body: chunkForm(hash, 0, 1, big.length, big.length, big, 'over-limit.bin') }))
    expect(r.status === 413 || r.status === 400, 'C2 超限分片(20MB+1)被拒 413/400', JSON.stringify(r))
    const health = await req(`${BASE}/files`)
    expect(health.status === 200, 'C2b 拒绝后服务仍健康')
    await req(`${BASE}/upload/${hash}`, { method: 'DELETE' })
  }
  mark('END', 'C-chunk-over-limit')

  mark('START', 'C-total-size-limit')
  {
    // C3 总大小上限 20GB：恰好 20GB 通过大小校验（因缺分片 409），20GB+1 → 413
    const hash = randHash()
    const GB20 = 20 * 1024 ** 3
    let r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'total-20gb.bin', totalSize: GB20, totalChunks: 1, chunkSize: GB20 }))
    expect(r.status === 409, 'C3 恰好20GB 通过大小校验（缺分片409）', JSON.stringify(r))
    r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'total-20gb.bin', totalSize: GB20 + 1, totalChunks: 1, chunkSize: GB20 }))
    expect(r.status === 413, 'C3b 20GB+1 被拒 413', JSON.stringify(r))
    // C4 分片数上限 100000：100001 → 400
    r = await j(await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: 'chunks-over.bin', totalSize: 1048576, totalChunks: 100001, chunkSize: 1048576 }))
    expect(r.status === 400, 'C4 totalChunks=100001 被拒 400', JSON.stringify(r))
  }
  mark('END', 'C-total-size-limit')

  /* ---------- D. 1000 个 1MB 小文件并发上传（合并风暴） ---------- */
  mark('START', 'D-merge-storm-1000x1MB')
  {
    const FILE_COUNT = 1000, CONCURRENCY = 20, SIZE = 1048576
    let cursor = 0
    const hashes = []
    const stat = { chunk: 0, merged: 0, errors: [] }
    const t0 = Date.now()
    async function worker() {
      while (true) {
        const idx = cursor++
        if (idx >= FILE_COUNT) return
        const buf = Buffer.alloc(SIZE)
        crypto.randomFillSync(buf)
        const hash = crypto.createHash('md5').update(buf).digest('hex')
        try {
          let r = await req(`${BASE}/upload/chunk`, { method: 'POST', body: chunkForm(hash, 0, 1, SIZE, SIZE, buf, `storm-${idx}.bin`) })
          if (r.status !== 200) throw new Error(`chunk=${r.status}`)
          stat.chunk++
          r = await jsonReq(`${BASE}/upload/merge`, { fileHash: hash, fileName: `storm-${idx}.bin`, totalSize: SIZE, totalChunks: 1, chunkSize: SIZE })
          if (r.status !== 200) throw new Error(`merge=${r.status}`)
          stat.merged++
          hashes.push(hash)
        } catch (e) {
          stat.errors.push(`#${idx} ${hash.slice(0, 8)}: ${e.message}`)
        }
        if (idx % 200 === 0 && idx > 0) console.log(`  storm 进度 ${idx}/${FILE_COUNT}，已用时 ${((Date.now() - t0) / 1000).toFixed(0)}s`)
      }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, worker))
    const stormSec = (Date.now() - t0) / 1000
    mark('END', `D-merge-storm rc-errors=${stat.errors.length}`)
    console.log(`风暴完成: ${stormSec.toFixed(1)}s, 上传 ${stat.chunk}, 合并 ${stat.merged}, 错误 ${stat.errors.length}`)
    expect(stat.chunk === FILE_COUNT && stat.merged === FILE_COUNT && stat.errors.length === 0,
      'D1 1000个1MB文件并发上传+合并全部成功', stat.errors.slice(0, 5).join('; '))
    expect(stormSec > 0, `D1b 风暴耗时 ${stormSec.toFixed(1)}s（聚合 ${(FILE_COUNT / stormSec).toFixed(2)}MB/s）`)

    // D2 秒传抽查 + 列表数量
    let r = await j(await jsonReq(`${BASE}/upload/check`, { fileHash: hashes[0], fileName: 'storm-0.bin', totalSize: SIZE, totalChunks: 1, chunkSize: SIZE }))
    expect(r.body.finished === true, 'D2 风暴后秒传命中抽查', JSON.stringify(r.body))
    const list = await j(await req(`${BASE}/files`))
    expect(list.body.length === baselineCount + FILE_COUNT, 'D3 文件列表数量正确', `期望 ${baselineCount + FILE_COUNT} 实际 ${list.body.length}`)

    // D4 并发清理
    mark('START', 'D-cleanup')
    let del = 0, delErr = []
    let dcur = 0
    async function deleter() {
      while (true) {
        const i = dcur++
        if (i >= hashes.length) return
        try {
          const rr = await req(`${BASE}/files/${hashes[i]}`, { method: 'DELETE' })
          if (rr.status === 200) del++; else delErr.push(`#${i}=${rr.status}`)
        } catch (e) { delErr.push(`#${i}=${e.message}`) }
      }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, deleter))
    const list2 = await j(await req(`${BASE}/files`))
    mark('END', `D-cleanup deleted=${del}`)
    expect(del === FILE_COUNT && list2.body.length === baselineCount,
      'D4 1000个文件并发清理完成且列表复原', `deleted=${del} 剩余=${list2.body.length} err=${delErr.slice(0, 3)}`)
  }
  mark('MARK', `result ok=${ok} fail=${fail}`)
  console.log(`\n${ok} passed, ${fail} failed`)
  fs.writeFileSync(`${OUT}/boundary-result.json`, JSON.stringify({ ok, fail, failures }, null, 2))
  process.exit(fail ? 1 : 0)
}

main().catch((e) => { console.error('边界测试脚本异常:', e); process.exit(1) })
