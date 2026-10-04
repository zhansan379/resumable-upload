import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  // 大文件分片上传不设超时，由 AbortController 控制中断
  timeout: 0
})

/** 秒传 / 断点续传探测：返回 { finished, uploadedChunks, url, ... } */
export const checkUpload = (data) => http.post('/upload/check', data).then((r) => r.data)

/** 上传单个分片；onProgress 为 axios onUploadProgress 回调，signal 用于暂停/取消 */
export const uploadChunk = (formData, { signal, onProgress } = {}) =>
  http.post('/upload/chunk', formData, { signal, onUploadProgress: onProgress }).then((r) => r.data)

/** 全部分片完成后发起合并 */
export const mergeChunks = (data) => http.post('/upload/merge', data).then((r) => r.data)

/** 已上传文件列表 */
export const listFiles = () => http.get('/files').then((r) => r.data)

/** 删除已上传文件 */
export const removeFile = (fileHash) => http.delete(`/files/${fileHash}`).then((r) => r.data)

/** 取消上传，清理服务端分片目录 */
export const cancelUpload = (fileHash) => http.delete(`/upload/${fileHash}`).then((r) => r.data)
