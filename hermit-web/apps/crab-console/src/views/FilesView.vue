<script setup lang="ts">
import { Delete, Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { copyText, notifyError } from '@shared/format'
import { api, type FileStageSettings, type FileStageView, type StagedFile } from '../api'

const MIB = 1024 * 1024
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const uploading = ref(false)
const view = ref<FileStageView | null>(null)
const fileInput = ref<HTMLInputElement>()
/** 页面上按分钟和 MiB 填，保存时换算成秒和字节。 */
const draft = reactive({ ttlMinutes: 60, maxFileMib: 50, maxTotalMib: 1024 })

const changed = computed(() => {
  const saved = view.value?.settings
  return !!saved && (
    saved.ttlSeconds !== draft.ttlMinutes * 60
    || saved.maxFileBytes !== draft.maxFileMib * MIB
    || saved.maxTotalBytes !== draft.maxTotalMib * MIB
  )
})

function size(bytes: number): string {
  if (bytes >= MIB) return `${(bytes / MIB).toFixed(1)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} 字节`
}

function fill(settings: FileStageSettings) {
  draft.ttlMinutes = Math.max(1, Math.round(settings.ttlSeconds / 60))
  draft.maxFileMib = Math.max(1, Math.round(settings.maxFileBytes / MIB))
  draft.maxTotalMib = Math.max(1, Math.round(settings.maxTotalBytes / MIB))
}

async function load() {
  loading.value = true
  try {
    const loaded = await api.files()
    view.value = { settings: loaded.settings, usage: loaded.usage, files: loaded.files ?? [] }
    fill(loaded.settings)
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await api.updateFileSettings({
      ttlSeconds: draft.ttlMinutes * 60,
      maxFileBytes: draft.maxFileMib * MIB,
      maxTotalBytes: draft.maxTotalMib * MIB,
    })
    ElMessage.success('已保存')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}

async function picked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploading.value = true
  try {
    const staged = await api.uploadFile(file)
    await copyText(staged.fileId, 'fileId 已复制，把它交给 Agent 填进文件参数')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    uploading.value = false
  }
}

async function remove(file: StagedFile) {
  try {
    await ElMessageBox.confirm(`删除后用到「${file.name}」的调用和下载链接都会失败。`, '删除这个文件？', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api.deleteFile(file.fileId)
    await load()
  } catch (error) {
    notifyError(error)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="files">
    <PageHeader
      title="文件暂存区"
      description="能力下载的文件和准备上传给老系统的文件临时放在这里。文件存在本机数据目录，到期自动删除，Crab 重启时清空"
    >
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Upload" :loading="uploading" data-test="file-upload" @click="fileInput?.click()">
        上传文件
      </el-button>
      <input ref="fileInput" type="file" hidden data-test="file-input" @change="picked" />
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      :title="view ? '文件暂存区刷新失败，下面是上一次读到的内容' : '文件暂存区读取失败'"
      data-test="files-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div class="hm-card" data-test="file-settings">
      <div class="hm-card-title"><span>设置</span></div>
      <div class="settings-row">
        <label class="field">
          <span class="hm-muted">有效期（分钟）</span>
          <el-input-number v-model="draft.ttlMinutes" :min="1" :max="10080" controls-position="right" data-test="file-ttl" />
        </label>
        <label class="field">
          <span class="hm-muted">单个文件上限（MiB）</span>
          <el-input-number v-model="draft.maxFileMib" :min="1" :max="1024" controls-position="right" data-test="file-max" />
        </label>
        <label class="field">
          <span class="hm-muted">总容量（MiB）</span>
          <el-input-number v-model="draft.maxTotalMib" :min="1" :max="102400" controls-position="right" data-test="file-total" />
        </label>
        <el-button type="primary" :disabled="!changed" :loading="saving" data-test="file-save" @click="save">保存</el-button>
      </div>
      <p class="hm-muted hint">
        新的有效期只对之后存入的文件生效。下载链接自带随机令牌，有效期内不需要 API Key 就能打开，拿到链接的人都能下载。
        文件内容不经过输出脱敏，调用记录和上报里只有文件名、大小、类型和 SHA-256。
      </p>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>文件</span>
        <span v-if="view" class="hm-muted usage" data-test="file-usage">
          {{ view.usage.files }} 个，共 {{ size(view.usage.bytes) }} / {{ size(view.settings.maxTotalBytes) }}
        </span>
      </div>
      <p class="hm-scroll-hint">表格可以左右滑动，删除按钮固定在最右侧。</p>
      <el-table :data="view?.files ?? []">
        <template #empty>{{ loading ? '' : loadFailed ? '没有读到文件列表' : '暂存区是空的' }}</template>
        <el-table-column label="文件名" min-width="180">
          <template #default="{ row }"><span class="file-name" :title="row.name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="fileId" min-width="220">
          <template #default="{ row }">
            <button type="button" class="id mono" title="复制 fileId" @click="copyText(row.fileId, 'fileId 已复制')">
              {{ row.fileId }}
            </button>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ size(row.size) }}</template>
        </el-table-column>
        <el-table-column label="谁能用" width="150">
          <template #default="{ row }">{{ row.shared ? '所有 API Key' : '创建它的 Key' }}</template>
        </el-table-column>
        <el-table-column label="到期" width="200">
          <template #default="{ row }"><TimeText :value="row.expiresAt" /></template>
        </el-table-column>
        <el-table-column label="" width="70" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :icon="Delete" :aria-label="`删除 ${row.name}`" @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
/* 长文件名最多两行，完整名字在悬停提示里。 */
.file-name {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  word-break: break-all;
}
.files {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.files .hm-card + .hm-card {
  margin-top: 0;
}

.settings-row {
  display: flex;
  align-items: flex-end;
  gap: 20px;
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
}

.hint {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
}

.usage {
  font-weight: 400;
  font-size: 12.5px;
}

.id {
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
  text-align: left;
  word-break: break-all;
}

.id:hover {
  color: var(--el-color-primary);
}
</style>
