import fs from 'node:fs'
import path from 'node:path'

// 边界测试分阶段内存/CPU 分析
const BASE = 'jmeter/results/boundary'
const idxRows = fs.readFileSync(path.join(BASE, 'boundary-index.csv'), 'utf8').trim().split('\n')
  .map(l => { const [ts, type, note] = l.split(','); return { ts: +ts, type, note: note || '' } })

const phases = []
for (let i = 0; i < idxRows.length; i++) {
  const r = idxRows[i]
  if (r.type === 'START') {
    const end = idxRows.find(x => x.type === 'END' && x.note.startsWith(r.note))
    phases.push({ name: r.note, start: r.ts, end: end ? end.ts : r.ts })
  }
}
const firstRow = idxRows[0]?.ts
const baseline = { name: 'baseline', start: firstRow - 60000, end: idxRows[0].ts }
const recEnd = idxRows.find(r => r.note === 'recovery-end')

const memRaw = fs.readFileSync(path.join(BASE, 'mem-jvm-inside.csv'), 'utf8').trim().split('\n')
const memLines = memRaw.filter(l => l.startsWith('ts,') || /^17\d{11,}/.test(l))
const header = memLines[0].split(',')
const mem = memLines.slice(1).map(l => { const o = {}; l.split(',').forEach((v, i) => o[header[i]] = +v); return o })
const docker = fs.readFileSync(path.join(BASE, 'mem-docker.csv'), 'utf8').trim().split('\n').map(l => {
  const m = l.match(/^(\d+)\s+\S+\s+([\d.]+)(MiB|GiB) \/ /)
  return m ? { ts: +m[1], usedMB: +m[2] * (m[3] === 'GiB' ? 1024 : 1), cpu: +l.match(/([\d.]+)%$/)[1] } : null
}).filter(Boolean)

function stats(p) {
  const rows = mem.filter(r => r.ts >= p.start && r.ts <= p.end)
  const drows = docker.filter(r => r.ts >= p.start && r.ts <= p.end)
  if (!rows.length) return null
  const mx = a => a.length ? Math.max(...a) : -1
  const gc0 = rows[0], gc1 = rows[rows.length - 1]
  return {
    n: rows.length,
    heapPeak: mx(rows.map(r => r.heapUsedMB)),
    oldPeak: mx(rows.map(r => r.oldUsedMB).filter(x => x >= 0)),
    oldEnd: rows[rows.length - 1].oldUsedMB,
    fullGC: gc1.gcOldCount - gc0.gcOldCount,
    threads: mx(rows.map(r => r.threadsLive)),
    dockerPeak: drows.length ? mx(drows.map(r => r.usedMB)) : -1,
    cpuPeak: drows.length ? mx(drows.map(r => r.cpu)) : -1,
    cpuAvg: drows.length ? +(drows.reduce((s, r) => s + r.cpu, 0) / drows.length).toFixed(1) : -1
  }
}

const fmt = v => (v == null || v < 0) ? '-' : (Number.isInteger(v) ? v : v.toFixed(1))
const lines = ['# 边界测试：服务端资源分阶段统计\n']
lines.push('| 阶段 | JVM样本 | 堆峰值(MB) | 老年代峰值(MB) | FullGC次 | 线程峰值 | 容器内存峰值(MB) | CPU峰值% | CPU均值% |')
lines.push('|------|---------|-----------|----------------|----------|----------|------------------|----------|----------|')
const row = (name, st) => lines.push(`| ${name} | ${st?.n ?? 0} | ${fmt(st?.heapPeak)} | ${fmt(st?.oldPeak)} | ${st ? st.fullGC : '-'} | ${st ? st.threads : '-'} | ${fmt(st?.dockerPeak)} | ${fmt(st?.cpuPeak)} | ${fmt(st?.cpuAvg)} |`)
row('baseline(空闲)', stats(baseline))
for (const p of phases) row(p.name, stats(p))
row('recovery(恢复期)', stats({ name: 'recovery', start: phases[phases.length - 1].end, end: recEnd?.ts ?? Date.now() }))
const report = lines.join('\n')
fs.writeFileSync(path.join(BASE, 'boundary-mem-report.md'), report + '\n')
console.log(report)

// SVG: 内存曲线（堆/老年代/容器）+ 阶段边界 —— 只画有 JVM 数据的窗口（容器内采样区间）
const t0 = mem[0].ts, t1 = mem[mem.length - 1].ts
const dMax = docker.length ? Math.max(...docker.map(r => r.usedMB)) : 0
const yMax = Math.max(247.5, dMax * 1.05)
const W = 1600, H = 500, L = 70, R = 20, T = 30, B = 90
const x = t => L + Math.max(0, (t - t0)) / (t1 - t0) * (W - L - R)
const y = v => T + (1 - v / yMax) * (H - T - B)
let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="sans-serif" font-size="13"><rect width="${W}" height="${H}" fill="#fafafa"/>`
for (let g = 0; g <= 4; g++) {
  const v = yMax / 4 * g, yy = y(v)
  s += `<line x1="${L}" y1="${yy}" x2="${W - R}" y2="${yy}" stroke="#ddd"/><text x="${L - 8}" y="${yy + 4}" text-anchor="end" fill="#666">${Math.round(v)}</text>`
}
for (const p of phases.filter(p => p.end >= t0 && p.start <= t1)) {
  const label = p.name.replace(/-\d+x?/, m => m).slice(0, 22)
  s += `<line x1="${x(p.start)}" y1="${T}" x2="${x(p.start)}" y2="${H - B}" stroke="#999" stroke-dasharray="4 4"/><text x="${x(p.start) + 4}" y="${T + 12}" fill="#333">${label}</text>`
}
for (const [key, color] of [['heapUsedMB', '#1976d2'], ['oldUsedMB', '#e65100']]) {
  const pts = mem.filter(r => r[key] >= 0).map(r => `${x(r.ts)},${y(r[key])}`).join(' ')
  s += `<polyline points="${pts}" fill="none" stroke="${color}" stroke-width="1.6"/>`
}
const dpts = docker.filter(r => r.ts >= t0).map(r => `${x(r.ts)},${y(r.usedMB)}`).join(' ')
s += `<polyline points="${dpts}" fill="none" stroke="#2e7d32" stroke-width="1.4" opacity="0.8"/>`
s += `<text x="${L}" y="${H - 55}" fill="#1976d2">— heap used</text><text x="${L + 120}" y="${H - 55}" fill="#e65100">— old gen</text><text x="${L + 240}" y="${H - 55}" fill="#2e7d32">— container RSS</text>`
s += `<text x="${L}" y="${H - 30}" fill="#666">${new Date(t0).toLocaleString()}</text><text x="${W - R}" y="${H - 30}" fill="#666" text-anchor="end">${new Date(t1).toLocaleString()}</text></svg>`
fs.writeFileSync(path.join(BASE, 'memory-curve.svg'), s)
console.log('\nSVG ->', path.join(BASE, 'memory-curve.svg'))
