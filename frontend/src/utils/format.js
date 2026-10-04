/** 字节数格式化：1024 -> "1.0 KB" */
export function formatBytes(bytes, digits = 1) {
  if (bytes === 0 || bytes == null) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const v = bytes / 1024 ** i
  return `${v.toFixed(i === 0 ? 0 : digits)} ${units[i]}`
}

/** 速度格式化：bytes/s -> "2.5 MB/s" */
export function formatSpeed(bytesPerSec) {
  if (!bytesPerSec || bytesPerSec <= 0) return ''
  return `${formatBytes(bytesPerSec)}/s`
}

/** 上传任务状态 -> 展示信息 */
export const STATUS_MAP = {
  waiting: { label: '等待中', tag: 'info' },
  hashing: { label: '计算MD5', tag: 'warning' },
  checking: { label: '检查分片', tag: 'warning' },
  uploading: { label: '上传中', tag: 'primary' },
  paused: { label: '已暂停', tag: 'warning' },
  merging: { label: '合并中', tag: 'warning' },
  done: { label: '已完成', tag: 'success' },
  error: { label: '失败', tag: 'danger' }
}
