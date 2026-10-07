<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Uploader } from '../upload/uploader'
import { storeFile, restoreFile, unstoreFile, loadRecords, saveRecords } from '../upload/taskStore'
import { formatBytes, formatSpeed, STATUS_MAP } from '../utils/format'

/**
 * 大文件上传面板（可直接复制进宿主项目的 Vue 3 组件，不依赖任何 UI 库）：
 * - 组合 upload/ 下的 headless 核心（调度器 + MD5 Worker + 任务持久化）；
 * - 自带拖拽/选择、任务列表、进度条、暂停/恢复/重试/取消、刷新恢复与"待重选文件"流程；
 * - 样式全部 scoped，宿主可整段替换；对外只抛 uploaded / error 两个事件。
 * 事件：uploaded({ name, url, instant, fileHash })、error({ name, message })
 * 暴露：tasks（响应式任务数组）、addFiles(FileList)
 */
const props = defineProps({
  /** 单片大小（MB）。注意：分片大小是任务身份，改它只影响之后新添加的文件 */
  chunkSizeMb: { type: Number, default: 5 },
  /** 分片并发（1~6），弱网时调度器会在其基础上自动降并发 */
  concurrency: { type: Number, default: 3 },
  /** 页面恢复后是否自动续传在途任务（主动暂停/失败的任务永远等用户手动继续） */
  autoResume: { type: Boolean, default: true },
  /** 拖拽区提示文案 */
  tip: { type: String, default: '点击选择文件，或将文件拖拽到此处' }
})

const emit = defineEmits(['uploaded', 'error'])

const dragOver = ref(false)
const fileInput = ref(null)
const reselectTarget = ref(null)
const tasks = reactive([])

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
    chunkSize: props.chunkSizeMb * 1024 * 1024,
    concurrency: props.concurrency,
    initialHash: saved?.fileHash || null,
    onEvent: (e) => handleEvent(task, e)
  })
  return task
}

function addFiles(fileList) {
  for (const f of fileList) {
    if (f.size === 0) {
      emit('error', { name: f.name, message: '空文件无法分片上传' })
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
      task.message = ''
      task.instant = !!e.instant
      unstoreFile(task.id)
      syncRecords()
      emit('uploaded', { name: task.name, url: e.url, instant: !!e.instant, fileHash: task.fileHash })
      break
    case 'error':
      task.message = e.message
      syncRecords()
      emit('error', { name: task.name, message: e.message })
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

/** "待重选文件"任务：定向重选（IndexedDB 里的文件引用失效后，重选同名文件即可续上） */
function reselect(task) {
  reselectTarget.value = task
  fileInput.value?.click()
}

function onPick(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  const target = reselectTarget.value
  reselectTarget.value = null
  if (target) {
    const f = files[0]
    if (f && f.name === target.name && f.size === target.size) {
      const task = createTask(f, { id: target.id, fileHash: target.fileHash })
      tasks.splice(tasks.indexOf(target), 1, task)
      storeFile(task.id, f).catch(() => {})
      syncRecords()
      task.uploader.start()
    }
    return
  }
  addFiles(files)
}

function onDrop(e) {
  dragOver.value = false
  addFiles(Array.from(e.dataTransfer?.files || []))
}

// 刷新/重开页面后恢复任务列表：
// - 上传中/等待中的任务按 autoResume 自动续传（复用持久化的 fileHash，跳过 MD5 重算）；
// - 用户主动暂停或出错的任务恢复为"已暂停"，等用户点继续；
// - IndexedDB 里的文件引用失效（如浏览器清理）时标记"待重选文件"，重选同名文件即可续上。
onMounted(async () => {
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
    // 先静默同步服务端实际进度（进度条直接对齐，且能发现"分片已齐"）
    const peek = r.fileHash ? await task.uploader.peekProgress() : { autoMerge: true }
    const shouldAutoResume = props.autoResume
      && ['waiting', 'hashing', 'checking', 'uploading', 'merging'].includes(r.status)
      && peek.autoMerge
    if (shouldAutoResume) {
      task.uploader.start()
    } else {
      // 主动暂停 / 上次出错 / 已暂停但分片已齐（不应自动合并）→ 恢复为已暂停等用户点继续
      task.uploader.markInterrupted()
      task.status = 'paused'
      task.message = r.message || ''
    }
  }
  syncRecords()
})

defineExpose({ tasks, addFiles })
</script>

<template>
  <div class="rup">
    <div
      class="rup-drop"
      :class="{ 'drag-over': dragOver }"
      @click="fileInput?.click()"
      @dragover.prevent="dragOver = true"
      @dragleave="dragOver = false"
      @drop.prevent="onDrop"
    >
      <svg class="cloud" viewBox="0 0 24 24" width="46" height="46" fill="none">
        <path
          d="M7 18a5 5 0 0 1-.9-9.92 6.5 6.5 0 0 1 12.65 1.55A4.5 4.5 0 0 1 17.5 18H7Z"
          stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"
        />
        <path d="M12 12v6m0-6-2.5 2.5M12 12l2.5 2.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <div class="tip">{{ tip }}</div>
      <div class="sub">分片上传 · 断点续传 · 秒传 · 暂停/恢复 · 刷新页面任务自动恢复</div>
    </div>
    <input ref="fileInput" type="file" multiple style="display: none" @change="onPick" />

    <div v-if="tasks.length" class="tasks">
      <div v-for="t in tasks" :key="t.id" class="task">
        <div class="row-1">
          <span class="name" :title="t.name">{{ t.name }}</span>
          <span class="tag" :class="'tag-' + (STATUS_MAP[t.status]?.tag || 'info')">
            {{ STATUS_MAP[t.status]?.label || t.status }}
          </span>
          <span v-if="t.status === 'done' && t.instant" class="tag tag-success">秒传</span>
        </div>
        <div class="row-2">
          <span>{{ formatBytes(t.size) }}</span>
          <span v-if="t.fileHash">MD5 {{ t.fileHash.slice(0, 8) }}…</span>
          <span v-if="t.status === 'hashing'">计算 {{ t.hashPercent }}%</span>
          <span v-if="t.status === 'uploading'">{{ formatSpeed(t.speed) }}</span>
        </div>
        <div class="bar">
          <div
            class="bar-fill"
            :class="{ ok: t.status === 'done', err: t.status === 'error' }"
            :style="{ width: Math.floor(t.percent) + '%' }"
          ></div>
        </div>
        <div v-if="t.message" class="err-msg" :title="t.message">{{ t.message }}</div>
        <div class="row-3">
          <button v-if="t.status === 'uploading'" class="btn" @click="pauseTask(t)">暂停</button>
          <button
            v-else-if="t.status === 'paused' || t.status === 'error'"
            class="btn primary"
            @click="resumeTask(t)"
          >
            {{ t.status === 'error' ? '重试' : '继续' }}
          </button>
          <button v-if="t.status === 'missing'" class="btn primary" @click="reselect(t)">选择文件</button>
          <button v-if="t.status === 'done'" class="btn text" @click="removeTask(t)">移除</button>
          <button class="btn text danger" @click="cancelTask(t)">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rup {
  width: 100%;
}

.rup-drop {
  border: 1.5px dashed #c0c4cc;
  border-radius: 8px;
  padding: 30px 16px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  color: #909399;
  background: #fafafa;
}

.rup-drop:hover,
.rup-drop.drag-over {
  border-color: #409eff;
  background: #ecf5ff;
  color: #409eff;
}

.rup-drop .tip {
  margin-top: 8px;
  font-size: 14px;
  color: #606266;
}

.rup-drop .sub {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.tasks {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 12px;
}

.row-1 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-1 .name {
  font-weight: 500;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
  border: 1px solid;
  flex-shrink: 0;
}

.tag-info { color: #909399; border-color: #d4d7de; background: #f4f4f5; }
.tag-warning { color: #b88230; border-color: #f3d19e; background: #fdf6ec; }
.tag-primary { color: #409eff; border-color: #a0cfff; background: #ecf5ff; }
.tag-success { color: #67c23a; border-color: #b3e19d; background: #f0f9eb; }
.tag-danger { color: #f56c6c; border-color: #fab6b6; background: #fef0f0; }

.row-2 {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #909399;
  margin: 4px 0 6px;
}

.bar {
  height: 6px;
  border-radius: 3px;
  background: #ebeef5;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: #409eff;
  transition: width 0.2s;
}

.bar-fill.ok { background: #67c23a; }
.bar-fill.err { background: #f56c6c; }

.err-msg {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-3 {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.btn {
  font-size: 12px;
  padding: 3px 12px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  cursor: pointer;
}

.btn:hover { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn.primary { color: #fff; background: #409eff; border-color: #409eff; }
.btn.primary:hover { background: #66b1ff; }
.btn.text { border-color: transparent; background: transparent; }
.btn.danger { color: #f56c6c; }
.btn.danger:hover { color: #f78989; background: #fef0f0; }
</style>
