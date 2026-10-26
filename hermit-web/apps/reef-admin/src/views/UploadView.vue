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
const fileName = ref('')
const result = ref<UploadResult | null>(null)
const failure = ref<{ message: string; errors: ValidationIssue[]; warnings: ValidationIssue[] } | null>(null)
const step = computed(() => (result.value ? 2 : uploading.value || failure.value ? 1 : 0))

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

async function onChange(file: UploadFile) {
  if (!file.raw) {
    return
  }
  fileName.value = file.name
  result.value = null
  failure.value = null
  uploading.value = true
  try {
    result.value = await api.upload(file.raw)
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
    await ElMessageBox.confirm(`发布后所有 Crab 下次同步时都会切换到 ${conch} v${version}。`, '确认发布', {
      confirmButtonText: '发布',
      cancelButtonText: '取消',
      type: 'warning',
    })
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
</script>

<template>
  <div>
    <PageHeader title="上传规则包" description="上传 YAML 后 Reef 会校验、签名并保存为待发布版本；版本号必须是比已有版本更大的 semver">
      <template #crumbs>
        <router-link to="/conches">规则包目录</router-link>
        <span class="hm-muted"> / 上传</span>
      </template>
    </PageHeader>

    <el-steps :active="step" finish-status="success" :process-status="failure ? 'error' : 'process'" align-center class="steps">
      <el-step title="选择 YAML" description="拖入或点击选择文件" />
      <el-step title="校验并签名" description="检查结构、能力和字段" />
      <el-step title="待发布" description="确认差异和警告" />
      <el-step title="发布" description="Crab 下次同步时切换" />
    </el-steps>

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
        </template>
      </el-upload>
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
.status {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-primary);
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
