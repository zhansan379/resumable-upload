<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Uploader } from './upload/uploader'
import { listFiles, removeFile } from './api'
import { storeFile, restoreFile, unstoreFile, loadRecords, saveRecords } from './upload/taskStore'
import { formatBytes, formatSpeed, STATUS_MAP } from './utils/format'

const chunkSizeMB = ref(5)
const concurrency = ref(3)
const dragOver = ref(false)
const fileInput = ref(null)
const tasks = reactive([])
const files = ref([])

function newTaskId() {
  // crypto.randomUUID 仅在 HTTPS/localhost 可用，非安全上下文（如 http://IP 访问）需降级
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `t-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

// 任务元数据写入 localStorage（进度不落盘：服务端磁盘是唯一事实源，恢复时重新 check 对齐）
// 已完成的任务不持久化——它已进入服务器文件列表，恢复它只会产生"待重选文件"噪声
function syncRecords() {
  saveRecords(tasks
    .filter((t) => t.status !== 'done')
    .map((t) => ({
      id: t.id,
      name: t.name,
      size: t.size,
      lastModified: t.lastModified,
      type: t.type,
      fileHash: t.fileHash,
      status: t.status,
      message: t.message
    })))
}

function createTask(file, saved = null) {
  const task = reactive({
    id: saved?.id || newTaskId(),
    name: file.name,
    size: file.size,
    lastModified: file.lastModified,
    type: file.type,
    percent: 0,
    speed: 0,
    hashPercent: 0,
    fileHash: saved?.fileHash || '',
    status: 'waiting',
    message: saved?.message || '',
    instant: false,
    uploader: null
  })
  task.uploader = new Uploader(file, {
    chunkSize: chunkSizeMB.value * 1024 * 1024,
    concurrency: concurrency.value,
    initialHash: saved?.fileHash || null,
    onEvent: (e) => handleEvent(task, e)
  })
  return task
}

function addFiles(fileList) {
  for (const f of fileList) {
    if (f.size === 0) {
      ElMessage.warning(`空文件无法分片上传：${f.name}`)
      continue
    }
    // 与"待重选文件"的恢复记录配对：同名同大小的文件直接顶替失效记录
    const missing = tasks.find((t) => t.status === 'missing' && t.name === f.name && t.size === f.size)
    const task = createTask(f, missing ? { id: missing.id } : null)
    if (missing) {
      tasks.splice(tasks.indexOf(missing), 1, task)
    } else {
      tasks.unshift(task)
    }
    storeFile(task.id, f).catch(() => {})
    syncRecords()
    task.uploader.start()
  }
}

function handleEvent(task, e) {
  switch (e.type) {
    case 'hash-progress':
      task.hashPercent = e.percent
      break
    case 'hash':
      task.fileHash = e.fileHash
      syncRecords()
      break
    case 'progress':
      task.percent = e.percent
      task.speed = e.speed
      break
    case 'state':
      task.status = task.uploader.state
      syncRecords()
      break
    case 'done':
      task.status = 'done'
      task.percent = 100
      task.speed = 0
      task.instant = !!e.instant
      unstoreFile(task.id)
      syncRecords()
      refreshFiles()
      ElMessage.success(task.instant ? `秒传完成：${task.name}` : `上传完成：${task.name}`)
      break
    case 'error':
      task.message = e.message
      syncRecords()
      ElMessage.error(`${task.name}：${e.message}`)
      break
  }
}

function pauseTask(task) {
  task.uploader.pause()
}

function resumeTask(task) {
  if (task.status === 'error') {
    task.message = ''
    task.uploader.retry()
  } else {
    task.uploader.resume()
  }
}

async function cancelTask(task) {
  if (task.uploader) {
    task.uploader.cancel()
  }
  unstoreFile(task.id)
  const i = tasks.indexOf(task)
  if (i >= 0) tasks.splice(i, 1)
  syncRecords()
}

function removeTask(task) {
  const i = tasks.indexOf(task)
  if (i >= 0) tasks.splice(i, 1)
  syncRecords()
}

async function deleteFile(row) {
  try {
    await ElMessageBox.confirm(`确认删除服务器文件「${row.fileName}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeFile(row.fileHash)
    ElMessage.success('已删除')
    refreshFiles()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

function onPick(e) {
  addFiles(Array.from(e.target.files || []))
  e.target.value = ''
}

function onDrop(e) {
  dragOver.value = false
  addFiles(Array.from(e.dataTransfer?.files || []))
}

async function refreshFiles() {
  try {
    files.value = await listFiles()
  } catch {
    /* 服务端未就绪时静默 */
  }
}

// 刷新/重开页面后恢复任务列表：
// - 上传中/等待中的任务自动续传（复用持久化的 fileHash，跳过 MD5 重算）；
// - 用户主动暂停或出错的任务恢复为"已暂停"，等用户点继续；
// - IndexedDB 里的文件引用失效（如浏览器清理）时标记"待重选文件"，重选同名文件即可续上。
onMounted(async () => {
  refreshFiles()
  const records = loadRecords()
  for (const r of records) {
    let file = null
    try {
      file = await restoreFile(r.id)
    } catch {
      /* IndexedDB 不可用 */
    }
    if (!file || file.size !== r.size || file.name !== r.name) {
      tasks.unshift(reactive({
        id: r.id,
        name: r.name,
        size: r.size,
        lastModified: r.lastModified || 0,
        type: r.type || '',
        percent: 0,
        speed: 0,
        hashPercent: 0,
        fileHash: r.fileHash || '',
        status: 'missing',
        message: '浏览器中的文件引用已失效，重新选择同名文件即可继续上传',
        instant: false,
        uploader: null
      }))
      continue
    }
    const task = createTask(file, r)
    tasks.unshift(task)
    if (['waiting', 'hashing', 'checking', 'uploading', 'merging'].includes(r.status)) {
      task.uploader.start()
    } else {
      // 用户主动暂停 / 上次出错：不自动启动
      task.uploader.markInterrupted()
      task.status = 'paused'
      task.message = r.message || ''
    }
  }
  syncRecords()
})

// 仅开发模式：暴露任务列表给自动化测试/调试使用（生产构建不含）
if (import.meta.env.DEV) {
  window.__uploadTasks = tasks
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>大文件分片上传 · 断点续传 / 秒传</h1>
      <p>Spring Boot 3 + Vue 3 · 分片上传 / 暂停恢复 / 秒传 / 刷新恢复任务 / 合并后 MD5 校验</p>
    </header>

    <el-card class="card" shadow="never">
      <div class="settings">
        <span class="label">分片大小</span>
        <el-select v-model="chunkSizeMB" style="width: 110px">
          <el-option v-for="n in [1, 2, 5, 10, 20]" :key="n" :value="n" :label="`${n} MB`" />
        </el-select>
        <span class="label">分片并发</span>
        <el-input-number v-model="concurrency" :min="1" :max="6" />
        <span class="hint">设置仅对之后添加的文件生效</span>
      </div>

      <div
        class="drop-area"
        :class="{ 'drag-over': dragOver }"
        @click="fileInput?.click()"
        @dragover.prevent="dragOver = true"
        @dragleave="dragOver = false"
        @drop.prevent="onDrop"
      >
        <svg class="cloud" viewBox="0 0 24 24" width="52" height="52" fill="none">
          <path
            d="M7 18a5 5 0 0 1-.9-9.92 6.5 6.5 0 0 1 12.65 1.55A4.5 4.5 0 0 1 17.5 18H7Z"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linejoin="round"
          />
          <path d="M12 12v6m0-6-2.5 2.5M12 12l2.5 2.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <div class="tip">点击选择文件，或将文件拖拽到此处</div>
        <div class="sub">分片上传 · 断点续传 · 秒传 · 暂停/恢复 · 刷新页面任务自动恢复</div>
      </div>
      <input ref="fileInput" type="file" multiple style="display: none" @change="onPick" />

      <el-table v-if="tasks.length" :data="tasks" class="tasks">
        <el-table-column label="文件" min-width="240">
          <template #default="{ row }">
            <div class="task-name">{{ row.name }}</div>
            <div class="task-meta">
              <span>{{ formatBytes(row.size) }}</span>
              <span v-if="row.fileHash">MD5: {{ row.fileHash.slice(0, 8) }}…</span>
              <span v-if="row.status === 'hashing'">MD5 计算 {{ row.hashPercent }}%</span>
              <el-tag v-if="row.status === 'done' && row.instant" type="success" size="small">秒传</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="230">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.floor(row.percent)"
              :status="row.status === 'error' ? 'exception' : row.status === 'done' ? 'success' : undefined"
            />
          </template>
        </el-table-column>
        <el-table-column label="速度" width="110">
          <template #default="{ row }">
            {{ row.status === 'uploading' ? formatSpeed(row.speed) : '' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="STATUS_MAP[row.status]?.tag || 'info'" size="small">
              {{ STATUS_MAP[row.status]?.label || row.status }}
            </el-tag>
            <div v-if="row.message" class="task-err" :title="row.message">{{ row.message }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.status === 'uploading'" size="small" @click="pauseTask(row)">暂停</el-button>
            <el-button
              v-else-if="row.status === 'paused' || row.status === 'error'"
              size="small"
              type="primary"
              plain
              @click="resumeTask(row)"
            >
              {{ row.status === 'error' ? '重试' : '继续' }}
            </el-button>
            <el-button v-if="row.status === 'done'" size="small" text @click="removeTask(row)">移除</el-button>
            <el-button size="small" text type="danger" @click="cancelTask(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="card" shadow="never">
      <template #header>
        <div class="card-head">
          <span>服务器文件（合并完成）</span>
          <el-button size="small" @click="refreshFiles">刷新</el-button>
        </div>
      </template>
      <el-empty v-if="!files.length" description="暂无文件" :image-size="64" />
      <el-table v-else :data="files">
        <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="uploadTime" label="上传时间" width="170" />
        <el-table-column label="MD5 校验" width="120">
          <template #default="{ row }">
            <el-tag :type="row.verified ? 'success' : 'info'" size="small">
              {{ row.verified ? '通过' : '校验中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <a class="download" :href="row.downloadUrl" target="_blank">下载</a>
            <el-button size="small" text type="danger" @click="deleteFile(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}

.page-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.page-head p {
  margin: 0 0 18px;
  color: #909399;
  font-size: 13px;
}

.card {
  margin-bottom: 20px;
}

.settings {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.settings .label {
  color: #606266;
  font-size: 13px;
}

.settings .hint {
  color: #c0c4cc;
  font-size: 12px;
  margin-left: 4px;
}

.drop-area {
  border: 1.5px dashed #c0c4cc;
  border-radius: 8px;
  padding: 34px 16px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  color: #909399;
  background: #fafafa;
}

.drop-area:hover,
.drop-area.drag-over {
  border-color: #409eff;
  background: #ecf5ff;
  color: #409eff;
}

.drop-area .tip {
  margin-top: 8px;
  font-size: 14px;
  color: #606266;
}

.drop-area .sub {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.tasks {
  margin-top: 16px;
}

.task-name {
  font-weight: 500;
}

.task-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.task-err {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 4px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.download {
  margin-right: 10px;
  font-size: 12px;
  color: #409eff;
  text-decoration: none;
}

.download:hover {
  text-decoration: underline;
}
</style>
