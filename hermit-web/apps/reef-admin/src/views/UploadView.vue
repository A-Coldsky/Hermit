<script setup lang="ts">
import { Loading, Promotion, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { ApiError } from '@shared/http'
import { errorText, notifyError } from '@shared/format'
import { api, type UploadResult, type ValidationIssue } from '../api'
import DiffSummary from '../components/DiffSummary.vue'
import IssueList from '../components/IssueList.vue'

const router = useRouter()
const uploading = ref(false)
const publishing = ref(false)
const discarding = ref(false)
const fileName = ref('')
const pending = ref<File | null>(null)
const result = ref<UploadResult | null>(null)
const failure = ref<{ message: string; errors: ValidationIssue[]; warnings: ValidationIssue[] } | null>(null)
const step = computed(() => (result.value ? 2 : pending.value || uploading.value || failure.value ? 1 : 0))

function issuesOf(data: unknown, key: 'errors' | 'warnings'): ValidationIssue[] {
  if (data && typeof data === 'object' && Array.isArray((data as Record<string, unknown>)[key])) {
    return (data as Record<string, ValidationIssue[]>)[key]
  }
  return []
}

function parseIssue(data: unknown): ValidationIssue[] {
  if (data && typeof data === 'object' && 'message' in data && !('errors' in data)) {
    const d = data as { path?: string | null; message: string }
    return [{ code: 'PARSE_ERROR', path: d.path ?? '', message: d.message }]
  }
  return []
}

function onChange(file: UploadFile) {
  if (!file.raw) {
    return
  }
  fileName.value = file.name
  pending.value = file.raw
  result.value = null
  failure.value = null
}

function cancelPending() {
  pending.value = null
  fileName.value = ''
}

async function upload() {
  const file = pending.value
  if (!file) {
    return
  }
  pending.value = null
  uploading.value = true
  try {
    result.value = await api.upload(file)
    ElMessage.success(`已上传 ${result.value.conch} v${result.value.version}`)
  } catch (error) {
    if (error instanceof ApiError && error.status === 422) {
      failure.value = {
        message: '校验未通过，修正以下问题后重新上传',
        errors: [...issuesOf(error.data, 'errors'), ...parseIssue(error.data)],
        warnings: issuesOf(error.data, 'warnings'),
      }
      if (!failure.value.errors.length) {
        failure.value.message = errorText(error)
      }
    } else {
      failure.value = { message: errorText(error), errors: [], warnings: [] }
    }
  } finally {
    uploading.value = false
  }
}

async function publish() {
  if (!result.value) {
    return
  }
  const { conch, version } = result.value
  try {
    const added = result.value.diff?.newRequiredCredentials ?? []
    const credentials = added.length
      ? `这个版本新增必填凭证：${added.join('、')}。Crab 使用者要先填好这些凭证才能更新。`
      : ''
    await ElMessageBox.confirm(
      `发布后，新订阅的 Crab 直接安装 ${conch} v${version}；已订阅的 Crab 需要手动更新。${credentials}`,
      '确认发布',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  publishing.value = true
  try {
    await api.publish(conch, version)
    ElMessage.success(`${conch} v${version} 已发布`)
    await router.push(`/conches/${encodeURIComponent(conch)}`)
  } catch (error) {
    notifyError(error)
  } finally {
    publishing.value = false
  }
}

async function discard() {
  if (!result.value) {
    return
  }
  const { conch, version } = result.value
  try {
    await ElMessageBox.confirm(
      `丢弃后删除待发布的 ${conch} v${version}，同一个版本号可以重新上传。`,
      '丢弃这个版本',
      { confirmButtonText: '丢弃', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  discarding.value = true
  try {
    await api.discardVersion(conch, version)
    ElMessage.success(`已丢弃 ${conch} v${version}`)
    result.value = null
    fileName.value = ''
  } catch (error) {
    notifyError(error)
  } finally {
    discarding.value = false
  }
}

const STEPS = [
  { title: '选择 YAML', description: '拖入或点击选择文件' },
  { title: '确认上传', description: '校验结构、能力和字段并签名' },
  { title: '待发布', description: '确认差异和警告，不要就丢弃' },
  { title: '发布', description: '新订阅直接安装，已订阅的 Crab 手动更新' },
]
</script>

<template>
  <div>
    <PageHeader title="上传规则包" description="选好 YAML 并确认上传后，Reef 会校验、签名并保存为待发布版本；版本号必须是 semver，并且大于这个规则包现存的最高版本（不只是当前发布版本）。不想发布的待发布版本可以丢弃">
      <template #crumbs>
        <router-link to="/conches">规则包目录</router-link>
        <span class="hm-muted"> / 上传</span>
      </template>
    </PageHeader>

    <el-steps :active="step" finish-status="success" :process-status="failure ? 'error' : 'process'" align-center class="steps">
      <el-step v-for="item in STEPS" :key="item.title" :title="item.title" :description="item.description" />
    </el-steps>
    <p v-if="STEPS[step]" class="step-now" data-test="step-now">第 {{ step + 1 }} 步：{{ STEPS[step].description }}</p>

    <div class="hm-card">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".yaml,.yml"
        :disabled="uploading"
        :on-change="onChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">把规则包 YAML 拖到这里，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 .yaml / .yml，单个文件不超过 1 MB</div>
          <div class="el-upload__tip" data-test="local-import-tip">
            Crab 控制台「导出 YAML」得到的本地规则包可以直接上传。上传后由 Reef 校验并签名，发布后订阅了它的 Crab 才会安装。
            Crab 本机仍有同名本地规则包时可以直接订阅：两份都保留，原来那份继续生效，在 Crab 的规则包详情页核对版本后切换。
          </div>
        </template>
      </el-upload>
      <div v-if="pending" class="pending" data-test="pending">
        <span>已选择 <span class="mono">{{ fileName }}</span>，还没有上传</span>
        <span>
          <el-button @click="cancelPending">取消</el-button>
          <el-button type="primary" data-test="confirm-upload" @click="upload">上传并校验</el-button>
        </span>
      </div>
      <div v-if="uploading" class="status">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span class="hm-muted">正在校验 {{ fileName }}…</span>
      </div>
    </div>

    <div v-if="failure" class="hm-card">
      <div class="hm-card-title"><span>上传失败：{{ fileName }}</span></div>
      <el-alert :title="failure.message" type="error" :closable="false" show-icon />
      <template v-if="failure.errors.length">
        <h4>错误（{{ failure.errors.length }}）</h4>
        <IssueList :issues="failure.errors" tone="error" />
      </template>
      <template v-if="failure.warnings.length">
        <h4>警告（{{ failure.warnings.length }}）</h4>
        <IssueList :issues="failure.warnings" tone="warning" />
      </template>
    </div>

    <div v-if="result" class="hm-card">
      <div class="hm-card-title">
        <span>
          已保存 <span class="mono">{{ result.conch }}</span>
          <el-tag effect="plain" class="mono">v{{ result.version }}</el-tag>
          <el-tag type="info" effect="plain">待发布</el-tag>
        </span>
        <div>
          <el-button type="danger" plain :loading="discarding" data-test="discard-upload" @click="discard">丢弃</el-button>
          <el-button @click="router.push(`/conches/${encodeURIComponent(result.conch)}`)">查看规则包</el-button>
          <el-button type="primary" :icon="Promotion" :loading="publishing" @click="publish">发布此版本</el-button>
        </div>
      </div>
      <template v-if="result.warnings.length">
        <h4>警告（{{ result.warnings.length }}）</h4>
        <IssueList :issues="result.warnings" tone="warning" />
      </template>
      <h4>与当前发布版本的差异</h4>
      <DiffSummary :diff="result.diff" />
    </div>
  </div>
</template>

<style scoped>
/* 窄屏放不下四段说明：只留标题，当前这一步的说明单独一行。 */
.step-now {
  display: none;
}
@media (max-width: 640px) {
  .steps :deep(.el-step__description) {
    display: none;
  }
  .steps :deep(.el-step__title) {
    font-size: 12px;
    line-height: 1.4;
  }
  .step-now {
    display: block;
    margin: 0 0 12px;
    font-size: 13px;
    color: var(--hm-muted);
    text-align: center;
  }
}
.status {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-primary);
}

.pending {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.steps {
  margin: 4px 0 20px;
}

:deep(.el-upload-dragger) {
  padding: 44px 20px;
  border: 1.5px dashed var(--hm-hover-border);
  border-radius: 12px;
  background: linear-gradient(180deg, var(--hm-surface), var(--hm-subtle));
  transition: border-color 0.15s, background 0.15s;
}

:deep(.el-upload-dragger:hover),
:deep(.el-upload-dragger.is-dragover) {
  border-color: var(--el-color-primary);
  background: var(--hm-teal-soft);
}

:deep(.el-icon--upload) {
  color: var(--el-color-primary-light-3);
  font-size: 56px;
}

h4 {
  margin: 18px 0 10px;
  font-size: 14px;
}
</style>
