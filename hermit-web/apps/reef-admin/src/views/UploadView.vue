<script setup lang="ts">
import { Promotion, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { ref } from 'vue'
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
    ElMessage.success(`已上传 ${result.value.shell} v${result.value.version}`)
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
  const { shell, version } = result.value
  try {
    await ElMessageBox.confirm(`发布后所有 Crab 下次同步时都会切换到 ${shell} v${version}。`, '确认发布', {
      confirmButtonText: '发布',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  publishing.value = true
  try {
    await api.publish(shell, version)
    ElMessage.success(`${shell} v${version} 已发布`)
    await router.push(`/shells/${encodeURIComponent(shell)}`)
  } catch (error) {
    notifyError(error)
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="上传 Shell" description="上传 YAML 后 Reef 会校验、签名并保存为待发布版本；版本号必须是比已有版本更大的 semver">
      <template #crumbs>
        <router-link to="/shells">Shell 目录</router-link>
        <span class="hm-muted"> / 上传</span>
      </template>
    </PageHeader>

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
        <div class="el-upload__text">把 Shell YAML 拖到这里，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 .yaml / .yml，单个文件不超过 1 MB</div>
        </template>
      </el-upload>
      <div v-if="uploading" class="hm-muted status">正在校验 {{ fileName }}…</div>
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
          已保存 <span class="mono">{{ result.shell }}</span>
          <el-tag effect="plain" class="mono">v{{ result.version }}</el-tag>
          <el-tag type="info" effect="plain">待发布</el-tag>
        </span>
        <div>
          <el-button @click="router.push(`/shells/${encodeURIComponent(result.shell)}`)">查看 Shell</el-button>
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
}

h4 {
  margin: 18px 0 10px;
  font-size: 14px;
}
</style>
