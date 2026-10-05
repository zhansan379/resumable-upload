/**
 * 任务持久化（参考 uppy Golden-Retriever / fine-uploader resume 思路）：
 * - localStorage 存任务元数据（名称、大小、fileHash、状态），刷新后恢复任务列表；
 * - IndexedDB 存 File 引用（Chromium 按磁盘引用存储，不拷贝内容），刷新后免重新选择文件；
 * - fileHash 一并持久化，恢复任务时跳过重复的 MD5 计算。
 * 服务端磁盘上的分片仍是进度的唯一事实源，这里只负责"把任务找回来"。
 */
const LS_KEY = 'resumable-upload-tasks'
const DB_NAME = 'resumable-upload'
const STORE = 'files'

let dbPromise = null

function openDb() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1)
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains(STORE)) {
          req.result.createObjectStore(STORE)
        }
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  }
  return dbPromise
}

function toPromise(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export async function storeFile(id, file) {
  const db = await openDb()
  const tx = db.transaction(STORE, 'readwrite')
  await toPromise(tx.objectStore(STORE).put(file, id))
}

export async function restoreFile(id) {
  const db = await openDb()
  return toPromise(db.transaction(STORE).objectStore(STORE).get(id))
}

export async function unstoreFile(id) {
  try {
    const db = await openDb()
    const tx = db.transaction(STORE, 'readwrite')
    await toPromise(tx.objectStore(STORE).delete(id))
  } catch {
    /* IndexedDB 不可用时忽略 */
  }
}

export function loadRecords() {
  try {
    const list = JSON.parse(localStorage.getItem(LS_KEY) || '[]')
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

export function saveRecords(list) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(list))
  } catch {
    /* 存储满等异常忽略 */
  }
}
