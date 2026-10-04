import crypto from 'node:crypto'

// 可用 BASE_URL 指向任意环境，如: BASE_URL=http://<服务器IP>/api node scripts/e2e-backend.mjs
const BASE = process.env.BASE_URL || 'http://localhost:8080/api'
const CHUNK = 5 * 1024 * 1024

// 12MB 随机内容 → 5+5+2 共 3 片
const file = Buffer.alloc(12 * 1024 * 1024)
for (let i = 0; i < file.length; i += 65536) {
  crypto.randomFillSync(file, i, Math.min(65536, file.length - i))
}
const md5 = crypto.createHash('md5').update(file).digest('hex')
const fileName = 'e2e-test-测试.bin'

let ok = 0
let fail = 0
function expect(cond, name, extra = '') {
  if (cond) {
    ok++
    console.log('PASS', name)
  } else {
    fail++
    console.log('FAIL', name, extra)
  }
}

const j = async (res) => ({ status: res.status, body: await res.json().catch(() => null) })

async function check() {
  return j(await fetch(`${BASE}/upload/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileHash: md5, fileName, totalSize: file.length, totalChunks: 3, chunkSize: CHUNK })
  }))
}

async function uploadChunk(i) {
  const start = i * CHUNK
  const blob = new Blob([file.subarray(start, Math.min(file.length, start + CHUNK))])
  const fd = new FormData()
  fd.append('fileHash', md5)
  fd.append('chunkIndex', String(i))
  fd.append('totalChunks', '3')
  fd.append('chunkSize', String(CHUNK))
  fd.append('totalSize', String(file.length))
  fd.append('fileName', fileName)
  fd.append('chunk', blob, `chunk-${i}`)
  return j(await fetch(`${BASE}/upload/chunk`, { method: 'POST', body: fd }))
}

async function merge() {
  return j(await fetch(`${BASE}/upload/merge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileHash: md5, fileName, totalSize: file.length, totalChunks: 3, chunkSize: CHUNK })
  }))
}

async function main() {
  // 0. 服务就绪探测
  for (let i = 0; i < 30; i++) {
    try {
      await fetch(`${BASE}/files`)
      break
    } catch {
      await new Promise((r) => setTimeout(r, 1000))
    }
  }

  // 1. 初次 check：未传
  let r = await check()
  expect(r.status === 200 && r.body.finished === false && r.body.uploadedChunks.length === 0,
    '1 初次 check 未传', JSON.stringify(r.body))

  // 2. 上传分片 0
  r = await uploadChunk(0)
  expect(r.status === 200 && r.body.chunkIndex === 0 && r.body.size === CHUNK, '2 分片0上传成功', JSON.stringify(r.body))

  // 3. check 返回已传 [0]
  r = await check()
  expect(r.status === 200 && r.body.uploadedChunks.length === 1 && r.body.uploadedChunks[0] === 0,
    '3 check 返回断点 [0]', JSON.stringify(r.body))

  // 4. 缺分片时合并 → 409
  r = await merge()
  expect(r.status === 409 && /缺失/.test(r.body?.message || ''), '4 缺分片合并返回409', JSON.stringify(r.body))

  // 5. 补齐分片 1、2 后合并成功
  await uploadChunk(1)
  await uploadChunk(2)
  r = await merge()
  expect(r.status === 200 && r.body.fileHash === md5 && r.body.size === file.length, '5 合并成功', JSON.stringify(r.body))

  // 6. 二次 check → 秒传命中
  r = await check()
  expect(r.body.finished === true, '6 秒传命中', JSON.stringify(r.body))

  // 7. 下载并比对 MD5
  const dl = await fetch(new URL(r.body.url, BASE))
  const buf = Buffer.from(await dl.arrayBuffer())
  const dlMd5 = crypto.createHash('md5').update(buf).digest('hex')
  expect(buf.length === file.length && dlMd5 === md5, '7 下载内容 MD5 一致', `${buf.length} ${dlMd5}`)

  // 8. 非法 hash 参数 → 400
  r = await j(await fetch(`${BASE}/upload/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileHash: '../../hack', fileName, totalSize: 1, totalChunks: 1, chunkSize: CHUNK })
  }))
  expect(r.status === 400, '8 非法 hash 被拒绝', JSON.stringify(r))

  // 9. 分片重传幂等（覆盖同分片）
  await uploadChunk(0) // 先制造一个孤儿分片
  r = await j(await fetch(`${BASE}/upload/${md5}`, { method: 'DELETE' }))
  expect(r.status === 200 && r.body.cleaned === true, '9 取消上传清理分片', JSON.stringify(r.body))
  r = await check()
  expect(r.body.uploadedChunks.length === 0, '10 清理后分片为空', JSON.stringify(r.body))

  // 11. 删除文件
  r = await j(await fetch(`${BASE}/files/${md5}`, { method: 'DELETE' }))
  expect(r.status === 200 && r.body.deleted === true, '11 删除文件', JSON.stringify(r.body))
  r = await check()
  expect(r.body.finished === false, '12 删除后秒传失效', JSON.stringify(r.body))

  console.log(`\n${ok} passed, ${fail} failed`)
  process.exit(fail ? 1 : 0)
}

main().catch((e) => {
  console.error('E2E 脚本异常:', e)
  process.exit(1)
})
