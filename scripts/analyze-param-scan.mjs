import fs from 'node:fs'
import path from 'node:path'

// 参数扫描结果分析：汇总 .jtl 性能数据 + 内存 CSV 分阶段统计，生成 Markdown 报告与 SVG 内存曲线
const BASE = 'jmeter/results/scan-params'
const COMBOS = []
for (const s of [2, 10, 20]) for (const k of [1, 3, 6]) COMBOS.push({ s, k })

// ---------- 解析 scan-index.csv ----------
const indexRows = fs.readFileSync(path.join(BASE, 'scan-index.csv'), 'utf8').trim().split('\n')
  .map(l => { const [ts, type, note] = l.split(','); return { ts: Number(ts), type, note: note || '' } })
const phases = []
for (let i = 0; i < indexRows.length; i++) {
  const r = indexRows[i]
  if (r.type === 'START') {
    const end = indexRows.find(x => x.type === 'END' && x.note.startsWith(r.note))
    phases.push({ name: r.note, start: r.ts, end: end ? end.ts : r.ts })
  }
}
const baseline = { name: 'baseline', start: 1791249039417, end: indexRows[0]?.ts || 0 }
const comboPhases = phases.filter(p => /^c\d+m-k\d$/.test(p.name))
const recoveryStart = indexRows.find(r => r.note === 'recovery-90s-begin')
const recoveryEnd = indexRows.find(r => r.note === 'recovery-90s-end')
const recovery = recoveryStart && recoveryEnd ? [{ name: 'recovery-90s', start: recoveryStart.ts, end: recoveryEnd.ts }] : []

// ---------- 解析 mem-jvm.csv ----------
const memLines = fs.readFileSync(path.join(BASE, 'mem-jvm.csv'), 'utf8').trim().split('\n')
const memHeader = memLines[0].split(',')
const mem = memLines.slice(1).map(l => {
  const v = l.split(',')
  const o = {}
  memHeader.forEach((h, i) => (o[h] = Number(v[i])))
  return o
}).filter(o => o.ts > 0)

// ---------- 解析 mem-docker.csv ----------
let docker = []
const dockerFile = path.join(BASE, 'mem-docker.csv')
if (fs.existsSync(dockerFile)) {
  docker = fs.readFileSync(dockerFile, 'utf8').trim().split('\n').map(l => {
    const m = l.match(/^(\d+)\s+\S+\s+([\d.]+)(MiB|GiB) \/ ([\d.]+)(MiB|GiB)\s+([\d.]+)%/)
    if (!m) return null
    const used = Number(m[2]) * (m[3] === 'GiB' ? 1024 : 1)
    return { ts: Number(m[1]), usedMB: used, cpu: Number(m[6]) }
  }).filter(Boolean)
}

// ---------- 分阶段内存统计 ----------
function memStats(phase) {
  const rows = mem.filter(r => r.ts >= phase.start && r.ts <= phase.end)
  const drows = docker.filter(r => r.ts >= phase.start && r.ts <= phase.end)
  if (!rows.length) return null
  const pick = f => rows.map(f).filter(x => x >= 0)
  const mx = a => a.length ? Math.max(...a) : -1
  const avg = a => a.length ? a.reduce((s, x) => s + x, 0) / a.length : -1
  const gcDelta = (() => {
    const f = rows[0], l = rows[rows.length - 1]
    return { oC: l.gcOldCount - f.gcOldCount, yC: l.gcYoungCount - f.gcYoungCount, oT: l.gcOldTimeMs - f.gcOldTimeMs }
  })()
  // 老年代稳定水位：阶段内后 25% 样本的 oldUsed 均值（避开峰值毛刺）
  const tail = rows.slice(Math.floor(rows.length * 0.75))
  return {
    samples: rows.length,
    heapPeak: mx(pick(r => r.heapUsedMB)),
    heapTail: avg(tail.map(r => r.heapUsedMB)),
    oldPeak: mx(pick(r => r.oldUsedMB)),
    oldTail: avg(tail.map(r => r.oldUsedMB)),
    committed: avg(rows.map(r => r.heapCommittedMB)),
    threadsPeak: mx(pick(r => r.threadsLive)),
    fullGC: gcDelta.oC, fullGCTime: gcDelta.oT, youngGC: gcDelta.yC,
    dockerPeak: drows.length ? mx(drows.map(r => r.usedMB)) : -1,
    dockerAvg: drows.length ? avg(drows.map(r => r.usedMB)) : -1,
    cpuPeak: drows.length ? mx(drows.map(r => r.cpu)) : -1
  }
}

// ---------- 解析 .jtl ----------
function jtlStats(combo) {
  const f = path.join(BASE, 'jtl', `c${combo.s}m-k${combo.k}.jtl`)
  if (!fs.existsSync(f)) return null
  const rows = fs.readFileSync(f, 'utf8').trim().split('\n').slice(1)
    .map(l => l.split(','))
    .filter(c => c.length > 15)
    .map(c => ({
      ts: Number(c[0]), elapsed: Number(c[1]), label: c[2], code: c[3],
      ok: c[7] === 'true', bytes: Number(c[8]), sent: Number(c[9]), all: Number(c[13])
    }))
  if (!rows.length) return null
  const labelOf = l => {
    if (l.includes('check')) return 'check'
    if (l.includes('chunk')) return 'chunk'
    if (l.includes('merge')) return 'merge'
    if (l.includes('DELETE')) return 'delete'
    return 'other'
  }
  const byLabel = {}
  for (const r of rows) {
    const g = (byLabel[labelOf(r.label)] ||= [])
    g.push(r)
  }
  const stat = g => {
    const el = g.map(r => r.elapsed).sort((a, b) => a - b)
    return { n: g.length, err: g.filter(r => !r.ok).length, avg: Math.round(el.reduce((s, x) => s + x, 0) / el.length), p95: el[Math.floor(el.length * 0.95)] ?? el[el.length - 1], max: el[el.length - 1] }
  }
  const chunks = byLabel.chunk || []
  const t0 = Math.min(...chunks.map(r => r.ts))
  const t1 = Math.max(...chunks.map(r => r.ts + r.elapsed))
  const uploadSec = (t1 - t0) / 1000
  const totalMB = 100 * combo.k
  const mbps = uploadSec > 0 ? totalMB / uploadSec : 0
  return {
    total: rows.length, err: rows.filter(r => !r.ok).length,
    check: stat(byLabel.check || []), chunk: stat(chunks), merge: stat(byLabel.merge || []), del: stat(byLabel.delete || []),
    uploadSec, mbps
  }
}

// ---------- 输出 ----------
const lines = []
lines.push('# 参数扫描结果（远程 60.205.230.218:8080，SerialGC，Xmx256m，容器 512M）\n')
lines.push(`生成时间: ${new Date().toLocaleString()}  |  9 组: 2/10/20MB 分片 × 并发 1/3/6，每组上传 5 个独立 100MB 文件/线程（每线程 1 个文件）\n`)
const fmt = v => v < 0 ? '-' : (typeof v === 'number' ? (Number.isInteger(v) ? v : v.toFixed(1)) : v)
lines.push('| 组 | 错误 | 分片平均/P95(ms) | 合并(ms) | 上传耗时(s) | 聚合吞吐(MB/s) |')
lines.push('|----|------|------------------|----------|-------------|----------------|')
for (const c of COMBOS) {
  const j = jtlStats(c)
  lines.push(`| ${c.s}MB / 并发${c.k} | ${j ? `${j.err}/${j.total}` : '-'} | ${j ? `${j.chunk.avg} / ${j.chunk.p95}` : '-'} | ${j ? j.merge.avg : '-'} | ${j ? fmt(j.uploadSec) : '-'} | ${j ? fmt(j.mbps) : '-'} |`)
}
lines.push('')
lines.push('## 服务端内存分阶段统计（JVM JMX 1s 采样 + docker stats）\n')
lines.push('| 阶段 | 堆峰值(MB) | 堆尾段均值(MB) | 老年代峰值(MB) | 老年代尾段(MB) | 堆提交(MB) | FullGC 次/耗时ms | YoungGC 次 | 容器内存峰值(MB) | CPU峰值% | 线程峰值 |')
lines.push('|------|-----------|----------------|----------------|----------------|------------|------------------|-----------|------------------|----------|----------|')
const row = (name, st) => lines.push(`| ${name} | ${fmt(st?.heapPeak)} | ${fmt(st?.heapTail)} | ${fmt(st?.oldPeak)} | ${fmt(st?.oldTail)} | ${fmt(st?.committed)} | ${st ? `${st.fullGC} / ${st.fullGCTime}` : '-'} | ${st ? st.youngGC : '-'} | ${fmt(st?.dockerPeak)} | ${fmt(st?.cpuPeak)} | ${st ? st.threadsPeak : '-'} |`)
row('baseline(空闲)', memStats(baseline))
for (const p of comboPhases) {
  const st = memStats(p)
  const [size, k] = p.name.match(/c(\d+)m-k(\d)/).slice(1)
  row(`${size}MB/并发${k}`, st)
}
if (recovery.length) row('recovery(空置90s)', memStats(recovery[0]))
lines.push('')

// SVG 内存曲线
function svg() {
  const W = 1600, H = 500, L = 70, R = 20, T = 30, B = 90
  const t0 = baseline.start, t1 = mem[mem.length - 1].ts
  const series = [
    { key: 'heapUsedMB', color: '#1976d2', label: 'JVM heap used' },
    { key: 'oldUsedMB', color: '#e65100', label: 'old gen used' },
  ]
  const dMax = docker.length ? Math.max(...docker.map(r => r.usedMB)) : 0
  const yMax = Math.max(247.5, dMax * 1.05)
  const x = t => L + (t - t0) / (t1 - t0) * (W - L - R)
  const y = v => T + (1 - v / yMax) * (H - T - B)
  let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="sans-serif" font-size="13">`
  s += `<rect width="${W}" height="${H}" fill="#fafafa"/>`
  for (let g = 0; g <= 4; g++) {
    const v = yMax / 4 * g, yy = y(v)
    s += `<line x1="${L}" y1="${yy}" x2="${W - R}" y2="${yy}" stroke="#ddd"/><text x="${L - 8}" y="${yy + 4}" text-anchor="end" fill="#666">${Math.round(v)}</text>`
  }
  // 组边界
  for (const p of comboPhases) {
    const [size, k] = p.name.match(/c(\d+)m-k(\d)/).slice(1)
    s += `<line x1="${x(p.start)}" y1="${T}" x2="${x(p.start)}" y2="${H - B}" stroke="#999" stroke-dasharray="4 4"/>`
    s += `<text x="${x(p.start) + 4}" y="${T + 12}" fill="#333">${size}MB/k${k}</text>`
  }
  for (const ser of series) {
    const pts = mem.filter(r => r[ser.key] >= 0).map(r => `${x(r.ts)},${y(r[ser.key])}`).join(' ')
    s += `<polyline points="${pts}" fill="none" stroke="${ser.color}" stroke-width="1.6"/>`
  }
  if (docker.length) {
    const pts = docker.filter(r => r.ts >= t0).map(r => `${x(r.ts)},${y(r.usedMB)}`).join(' ')
    s += `<polyline points="${pts}" fill="none" stroke="#2e7d32" stroke-width="1.4" opacity="0.8"/>`
  }
  s += `<text x="${L}" y="${H - 55}" fill="#1976d2">— heap used</text><text x="${L + 120}" y="${H - 55}" fill="#e65100">— old gen</text><text x="${L + 240}" y="${H - 55}" fill="#2e7d32">— container RSS(docker)</text>`
  s += `<text x="${L}" y="${H - 30}" fill="#666">${new Date(t0).toLocaleString()}</text><text x="${W - R}" y="${H - 30}" fill="#666" text-anchor="end">${new Date(t1).toLocaleString()}</text>`
  s += `<text x="${L - 55}" y="${T - 12}" fill="#333">MB</text></svg>`
  return s
}
fs.writeFileSync(path.join(BASE, 'memory-curve.svg'), svg())

// CPU 曲线（docker stats 口径：100% = 1 核，服务器 2 vCPU，上限 200%）
function cpuSvg() {
  const W = 1600, H = 500, L = 70, R = 20, T = 30, B = 90
  const t0 = baseline.start, t1 = mem[mem.length - 1].ts
  const rows = docker.filter(r => r.ts >= t0)
  if (!rows.length) return ''
  const yMax = 200
  const x = t => L + (t - t0) / (t1 - t0) * (W - L - R)
  const y = v => T + (1 - v / yMax) * (H - T - B)
  let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="sans-serif" font-size="13">`
  s += `<rect width="${W}" height="${H}" fill="#fafafa"/>`
  for (let g = 0; g <= 4; g++) {
    const v = yMax / 4 * g, yy = y(v)
    s += `<line x1="${L}" y1="${yy}" x2="${W - R}" y2="${yy}" stroke="#ddd"/><text x="${L - 8}" y="${yy + 4}" text-anchor="end" fill="#666">${Math.round(v)}%</text>`
  }
  // 100% 参考线 = 1 个整核
  s += `<line x1="${L}" y1="${y(100)}" x2="${W - R}" y2="${y(100)}" stroke="#e65100" stroke-dasharray="6 4" opacity="0.6"/><text x="${W - R - 4}" y="${y(100) - 6}" text-anchor="end" fill="#e65100">100% = 1 core (2 vCPU total)</text>`
  for (const p of comboPhases) {
    const [size, k] = p.name.match(/c(\d+)m-k(\d)/).slice(1)
    s += `<line x1="${x(p.start)}" y1="${T}" x2="${x(p.start)}" y2="${H - B}" stroke="#999" stroke-dasharray="4 4"/>`
    s += `<text x="${x(p.start) + 4}" y="${T + 12}" fill="#333">${size}MB/k${k}</text>`
  }
  const pts = rows.map(r => `${x(r.ts)},${y(Math.min(r.cpu, yMax))}`).join(' ')
  s += `<polyline points="${pts}" fill="none" stroke="#455a64" stroke-width="1.4"/>`
  const peak = Math.max(...rows.map(r => r.cpu))
  const peakRow = rows.find(r => r.cpu === peak)
  s += `<circle cx="${x(peakRow.ts)}" cy="${y(peak)}" r="4" fill="#d32f2f"/><text x="${x(peakRow.ts) + 8}" y="${y(peak) - 8}" fill="#d32f2f">peak ${peak}%</text>`
  s += `<text x="${L}" y="${H - 55}" fill="#455a64">— container CPU (docker stats, ~2s sampling)</text>`
  s += `<text x="${L}" y="${H - 30}" fill="#666">${new Date(t0).toLocaleString()}</text><text x="${W - R}" y="${H - 30}" fill="#666" text-anchor="end">${new Date(t1).toLocaleString()}</text>`
  s += `<text x="${L - 55}" y="${T - 12}" fill="#333">CPU</text></svg>`
  return s
}
const cpuChart = cpuSvg()
if (cpuChart) fs.writeFileSync(path.join(BASE, 'cpu-curve.svg'), cpuChart)
fs.writeFileSync(path.join(BASE, 'scan-report.md'), lines.join('\n') + '\n'
  + '图表：[memory-curve.svg](memory-curve.svg)（堆/老年代/容器内存） | [cpu-curve.svg](cpu-curve.svg)（容器 CPU，100%=1 核）\n')
console.log(lines.join('\n'))
console.log('\nSVG ->', path.join(BASE, 'memory-curve.svg'))
