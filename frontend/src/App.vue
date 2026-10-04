<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Uploader } from './upload/uploader'
import { listFiles, removeFile } from './api'
import { formatBytes, formatSpeed, STATUS_MAP } from './utils/format'

const chunkSizeMB = ref(5)
const concurrency = ref(3)
const dragOver = ref(false)
const fileInput = ref(null)
const tasks = reactive([])
const files = ref([])

onMounted(refreshFiles)

async function refreshFiles() {
  try {
    files.value = await listFiles()
  } catch {
    /* 服务端未就绪时静默 */
  }
}

function addFiles(fileList) {
  for (const f of fileList) {
    if (f.size === 0) {
      ElMessage.warning(`空文件无法分片上传：${f.name}`)
      continue
    }
    const task = reactive({
      id: crypto.randomUUID(),
      name: f.name,
      size: f.size,
      percent: 0,
      speed: 0,
      hashPercent: 0,
      fileHash: '',
      status: 'waiting',
      message: '',
      instant: false,
      uploader: null
    })
    task.uploader = new Uploader(f, {
      chunkSize: chunkSizeMB.value * 1024 * 1024,
      concurrency: concurrency.value,
      onEvent: (e) => handleEvent(task, e)
    })
    tasks.unshift(task)
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
      break
    case 'progress':
      task.percent = e.percent
      task.speed = e.speed
      break
    case 'state':
      task.status = task.uploader.state
      break
    case 'done':
      task.status = 'done'
      task.percent = 100
      task.speed = 0
      task.instant = !!e.instant
      refreshFiles()
      ElMessage.success(task.instant ? `秒传完成：${task.name}` : `上传完成：${task.name}`)
      break
    case 'error':
      task.message = e.message
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
  task.uploader.cancel()
  const i = tasks.indexOf(task)
  if (i >= 0) tasks.splice(i, 1)
}

function removeTask(task) {
  const i = tasks.indexOf(task)
  if (i >= 0) tasks.splice(i, 1)
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
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>大文件分片上传 · 断点续传 / 秒传</h1>
      <p>Spring Boot 3 + Vue 3 · 分片上传 / 暂停恢复 / 秒传 / 合并后 MD5 校验</p>
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
        <div class="sub">支持大文件分片上传 · 断点续传 · 秒传 · 暂停 / 恢复</div>
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
