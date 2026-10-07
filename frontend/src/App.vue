<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import UploadPanel from './components/UploadPanel.vue'
import { listFiles, removeFile } from './api'
import { formatBytes } from './utils/format'

/**
 * 演示页：只负责"壳"——参数选择器、通知、服务器文件管理。
 * 上传面板的全部行为在 components/UploadPanel.vue（可直接复制进宿主项目）。
 */
const chunkSizeMB = ref(5)
const concurrency = ref(3)
const panelRef = ref(null)
const files = ref([])

function onUploaded(e) {
  ElMessage.success(e.instant ? `秒传完成：${e.name}` : `上传完成：${e.name}`)
  refreshFiles()
}

function onUploadError(e) {
  ElMessage.error(`${e.name}：${e.message}`)
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

async function refreshFiles() {
  try {
    files.value = await listFiles()
  } catch {
    /* 服务端未就绪时静默 */
  }
}

onMounted(refreshFiles)

// 仅开发模式：暴露任务列表给自动化测试/调试使用（生产构建不含）
if (import.meta.env.DEV) {
  onMounted(() => {
    window.__uploadTasks = panelRef.value?.tasks || []
  })
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

      <UploadPanel
        ref="panelRef"
        :chunk-size-mb="chunkSizeMB"
        :concurrency="concurrency"
        @uploaded="onUploaded"
        @error="onUploadError"
      />
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
