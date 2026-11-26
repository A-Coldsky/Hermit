<script setup lang="ts">
import {
  Check,
  Close,
  CopyDocument,
  Document,
  Refresh,
  Upload,
  UploadFilled,
  View,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { copyText, errorText, notifyError, relativeTime } from '@shared/format'
import { ApiError } from '@shared/http'
import {
  api,
  type SubmissionDetail,
  type SubmissionStatus,
  type SubmissionSummary,
  type ValidationIssue,
} from '../api'
import { isAdmin, session } from '../auth'
import DiffSummary from '../components/DiffSummary.vue'
import DriverBadge from '../components/DriverBadge.vue'
import IssueList from '../components/IssueList.vue'
import { submissionStatusLabels } from '../labels'
import { refreshPendingSubmissions } from '../submissions'

const loading = ref(true)
const loadFailed = ref(false)
const filter = ref<'ALL' | SubmissionStatus>('ALL')
const items = ref<SubmissionSummary[]>([])

// 提交弹窗状态
const submitOpen = ref(false)
const submitting = ref(false)
const selectedFile = ref<File | null>(null)
const selectedFileName = ref('')
const submitNote = ref('')
const submitFailure = ref<{ message: string; errors: ValidationIssue[]; warnings: ValidationIssue[] } | null>(null)

// 详情抽屉状态
const drawerOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<SubmissionDetail | null>(null)
const yamlVisible = ref(false)
const yamlLoading = ref(false)
const yamlContent = ref('')
const reviewComment = ref('')
const reviewError = ref('')
const actionBusy = ref(false)

const pageTitle = computed(() => (isAdmin.value ? '规则审批' : '规则提交'))
const pageDesc = computed(() =>
  isAdmin.value
    ? '审核成员提交的规则包（YAML / ZIP），查看警告与差异后可一键批准并签名发布，或填写原因驳回'
    : '提交本地调试好的规则包（YAML / ZIP）供管理员审批；待审批的提案不会占用正式版本号',
)

function statusTagType(status: SubmissionStatus): 'warning' | 'success' | 'danger' | 'info' {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

function canWithdraw(row: { status: SubmissionStatus; submittedBy: string }): boolean {
  return row.status === 'PENDING' && row.submittedBy === session.user
}

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

async function load() {
  loading.value = true
  try {
    items.value = await api.submissions(filter.value)
    loadFailed.value = false
    void refreshPendingSubmissions()
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function openSubmitDialog() {
  selectedFile.value = null
  selectedFileName.value = ''
  submitNote.value = ''
  submitFailure.value = null
  submitOpen.value = true
}

function onFileChange(file: UploadFile) {
  if (!file.raw) return
  selectedFile.value = file.raw
  selectedFileName.value = file.name
  submitFailure.value = null
}

async function doSubmit() {
  if (!selectedFile.value) {
    submitFailure.value = { message: '请先选择要提交的规则包文件（.yaml / .yml / .zip）', errors: [], warnings: [] }
    return
  }
  if (submitNote.value.trim().length > 500) {
    submitFailure.value = { message: '提交说明不能超过 500 个字符', errors: [], warnings: [] }
    return
  }
  submitting.value = true
  submitFailure.value = null
  try {
    const created = await api.submitConch(selectedFile.value, submitNote.value)
    ElMessage.success(`已提交 ${created.conch} v${created.version}，等待管理员审批`)
    submitOpen.value = false
    await load()
    openDetailFromValue(created)
  } catch (error) {
    if (error instanceof ApiError && error.status === 422) {
      const errors = [...issuesOf(error.data, 'errors'), ...parseIssue(error.data)]
      const warnings = issuesOf(error.data, 'warnings')
      submitFailure.value = {
        message: errors.length ? '规则包校验未通过，修正以下问题后重新提交' : errorText(error),
        errors,
        warnings,
      }
    } else {
      submitFailure.value = { message: errorText(error), errors: [], warnings: [] }
    }
  } finally {
    submitting.value = false
  }
}

function openDetailFromValue(value: SubmissionDetail) {
  detail.value = value
  yamlVisible.value = false
  yamlContent.value = ''
  reviewComment.value = ''
  reviewError.value = ''
  drawerOpen.value = true
}

async function openDetail(id: number) {
  drawerOpen.value = true
  detailLoading.value = true
  detail.value = null
  yamlVisible.value = false
  yamlContent.value = ''
  reviewComment.value = ''
  reviewError.value = ''
  try {
    detail.value = await api.submission(id)
  } catch (error) {
    notifyError(error)
    drawerOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function loadYaml() {
  if (!detail.value) return
  if (yamlVisible.value) {
    yamlVisible.value = false
    return
  }
  yamlVisible.value = true
  if (yamlContent.value) return
  yamlLoading.value = true
  try {
    yamlContent.value = await api.submissionYaml(detail.value.id)
  } catch (error) {
    notifyError(error)
    yamlVisible.value = false
  } finally {
    yamlLoading.value = false
  }
}

async function withdraw(id: number, label: string) {
  try {
    await ElMessageBox.confirm(`确认撤回 ${label} 的审批提交？撤回后可以重新修改并提交。`, '撤回提交', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  actionBusy.value = true
  try {
    const updated = await api.withdrawSubmission(id)
    ElMessage.success('已撤回该提交')
    if (detail.value?.id === id) {
      detail.value = updated
    }
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    actionBusy.value = false
  }
}

async function approve() {
  if (!detail.value) return
  const current = detail.value
  const addedCreds = current.diff?.newRequiredCredentials ?? []
  const credHint = addedCreds.length
    ? `该版本新增必填凭证：${addedCreds.join('、')}。`
    : ''
  try {
    await ElMessageBox.confirm(
      `批准后将立即用 Reef 私钥签名并发布 ${current.conch} v${current.version} 到正式目录。${credHint}`,
      '批准并发布规则包',
      { confirmButtonText: '批准并发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  reviewError.value = ''
  actionBusy.value = true
  try {
    const updated = await api.approveSubmission(current.id, reviewComment.value.trim() || undefined)
    detail.value = updated
    ElMessage.success(`${updated.conch} v${updated.version} 已批准并正式发布`)
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.data && typeof error.data === 'object') {
      const errs = (error.data as { errors?: ValidationIssue[] }).errors
      if (Array.isArray(errs) && errs.length) {
        reviewError.value = errs.map((e) => (e.path ? `${e.path}：${e.message}` : e.message)).join('；')
      }
    }
    notifyError(error)
  } finally {
    actionBusy.value = false
  }
}

async function reject() {
  if (!detail.value) return
  const comment = reviewComment.value.trim()
  if (!comment) {
    reviewError.value = '驳回提交时请填写驳回原因，方便提交人修改后重新提交'
    return
  }
  if (comment.length > 500) {
    reviewError.value = '驳回原因不能超过 500 个字符'
    return
  }
  reviewError.value = ''
  actionBusy.value = true
  try {
    const updated = await api.rejectSubmission(detail.value.id, comment)
    detail.value = updated
    ElMessage.success('已驳回该提交')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    actionBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader :title="pageTitle" :description="pageDesc">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Upload" data-test="open-submit-btn" @click="openSubmitDialog">
        提交规则包
      </el-button>
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="gap"
      title="读取审批列表失败，请重试"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div class="hm-card">
      <div class="hm-toolbar">
        <el-radio-group v-model="filter" size="small" data-test="status-filter" @change="load">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="PENDING">待审批</el-radio-button>
          <el-radio-button value="APPROVED">已批准发布</el-radio-button>
          <el-radio-button value="REJECTED">已驳回</el-radio-button>
          <el-radio-button value="WITHDRAWN">已撤回</el-radio-button>
        </el-radio-group>
        <span class="spacer" />
        <span class="hm-muted">共 {{ items.length }} 条</span>
      </div>

      <el-table v-loading="loading" :data="items" data-test="submissions-table">
        <template #empty>
          {{
            loading || loadFailed
              ? ''
              : filter === 'PENDING'
                ? '当前没有待审批的规则提交'
                : '暂无规则提交记录，点击右上角「提交规则包」开始'
          }}
        </template>
        <el-table-column prop="id" label="#" width="68" />
        <el-table-column label="规则包" min-width="200">
          <template #default="{ row }">
            <div class="conch-cell">
              <span class="mono">{{ row.conch }}</span>
              <el-tag size="small" effect="plain" class="mono">v{{ row.version }}</el-tag>
              <DriverBadge :driver="row.driver" />
            </div>
            <div class="hm-muted small">{{ row.title }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="118">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain" size="small">
              {{ submissionStatusLabels[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isAdmin" prop="submittedBy" label="提交人" width="120" />
        <el-table-column label="警告" width="76" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.warningCount > 0" type="warning" size="small" effect="plain">{{ row.warningCount }}</el-tag>
            <span v-else class="hm-muted">0</span>
          </template>
        </el-table-column>
        <el-table-column label="说明 / 审批意见" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.reviewComment" :class="row.status === 'REJECTED' ? 'reject-text' : ''">
              {{ row.reviewComment }}
            </span>
            <span v-else-if="row.submitNote">{{ row.submitNote }}</span>
            <span v-else class="hm-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="130">
          <template #default="{ row }">{{ relativeTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :icon="View" data-test="view-submission-btn" @click="openDetail(row.id)">
              {{ isAdmin && row.status === 'PENDING' ? '审批' : '详情' }}
            </el-button>
            <el-button
              v-if="canWithdraw(row)"
              size="small"
              text
              type="danger"
              data-test="withdraw-row-btn"
              @click="withdraw(row.id, `${row.conch} v${row.version}`)"
            >
              撤回
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 提交规则包弹窗 -->
    <el-dialog v-model="submitOpen" title="提交规则包审批" width="min(620px, 94vw)" destroy-on-close>
      <p class="hm-muted dialog-hint">
        支持上传在 Crab 控制台调试并导出的 <code class="mono">.yaml</code> / <code class="mono">.yml</code> 或
        <code class="mono">.zip</code> 文件（不超过 1 MB）。Reef 会执行完整语法与安全校验，校验通过后进入管理员审批队列。
      </p>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".yaml,.yml,.zip"
        :disabled="submitting"
        :on-change="onFileChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">把规则包文件拖到这里，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            <span v-if="selectedFileName">已选择：<strong class="mono">{{ selectedFileName }}</strong></span>
            <span v-else>支持 .yaml / .yml / .zip，单个文件不超过 1 MB</span>
          </div>
        </template>
      </el-upload>

      <el-form label-position="top" class="note-form">
        <el-form-item label="提交说明（可选，帮助管理员了解本次变更）">
          <el-input
            v-model="submitNote"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：新增采购审批查询能力，已在本机 Crab 开发者模式验证通过"
            data-test="submit-note-input"
          />
        </el-form-item>
      </el-form>

      <div v-if="submitFailure" class="failure-box" data-test="submit-failure">
        <el-alert :title="submitFailure.message" type="error" :closable="false" show-icon />
        <template v-if="submitFailure.errors.length">
          <h4>错误（{{ submitFailure.errors.length }}）</h4>
          <IssueList :issues="submitFailure.errors" tone="error" />
        </template>
        <template v-if="submitFailure.warnings.length">
          <h4>警告（{{ submitFailure.warnings.length }}）</h4>
          <IssueList :issues="submitFailure.warnings" tone="warning" />
        </template>
      </div>

      <template #footer>
        <el-button @click="submitOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" data-test="confirm-submit-btn" @click="doSubmit">
          校验并提交审批
        </el-button>
      </template>
    </el-dialog>

    <!-- 审批单详情抽屉 -->
    <el-drawer
      v-model="drawerOpen"
      :title="detail ? `审批单 #${detail.id} · ${detail.conch} v${detail.version}` : '审批单详情'"
      size="min(760px, 94vw)"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="drawer-badges">
            <el-tag :type="statusTagType(detail.status)" effect="dark">
              {{ submissionStatusLabels[detail.status] ?? detail.status }}
            </el-tag>
            <DriverBadge :driver="detail.driver" />
            <el-tag effect="plain" class="mono">min_crab_version ≥ {{ detail.minCrabVersion }}</el-tag>
            <router-link
              v-if="detail.currentPublishedVersion || detail.status === 'APPROVED'"
              :to="`/conches/${encodeURIComponent(detail.conch)}`"
              class="conch-link"
            >
              查看正式规则包 →
            </router-link>
          </div>

          <el-alert
            v-if="detail.status === 'PENDING' && detail.conchDisabled"
            type="error"
            show-icon
            :closable="false"
            class="gap"
            title="该规则包当前处于停用状态，启用前无法批准发布"
          />
          <el-alert
            v-if="detail.status === 'PENDING' && detail.versionConflict"
            type="warning"
            show-icon
            :closable="false"
            class="gap"
            :title="`版本冲突：正式目录已有同版本或更高版本（当前已发布 ${detail.currentPublishedVersion ? 'v' + detail.currentPublishedVersion : '无'}），需先处理已有版本或驳回此提交`"
          />

          <el-descriptions :column="2" border class="gap">
            <el-descriptions-item label="规则标题" :span="2">{{ detail.title }}</el-descriptions-item>
            <el-descriptions-item label="提交人">{{ detail.submittedBy }}</el-descriptions-item>
            <el-descriptions-item label="提交时间"><TimeText :value="detail.submittedAt" /></el-descriptions-item>
            <el-descriptions-item v-if="detail.authUse" label="依赖授权器" :span="2">
              <code class="mono small">{{ detail.authUse }}</code>
              <span v-if="detail.authMinVersion" class="hm-muted">（最低版本 v{{ detail.authMinVersion }}）</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.authExports?.length" label="导出变量" :span="2">
              <code class="mono small">{{ detail.authExports.join('、') }}</code>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.authAllowHosts?.length" label="允许的业务站点" :span="2">
              <code class="mono small">{{ detail.authAllowHosts.join('、') }}</code>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.submitNote" label="提交说明" :span="2">
              <span class="pre-wrap">{{ detail.submitNote }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.reviewedBy" label="审批人">{{ detail.reviewedBy }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.reviewedAt" label="处理时间">
              <TimeText :value="detail.reviewedAt" />
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.reviewComment" label="审批意见" :span="2">
              <span class="pre-wrap" :class="detail.status === 'REJECTED' ? 'reject-text' : ''">
                {{ detail.reviewComment }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="YAML SHA-256" :span="2">
              <code class="mono small">{{ detail.sha256 }}</code>
            </el-descriptions-item>
          </el-descriptions>

          <template v-if="detail.warnings.length">
            <h4>校验与安全警告（{{ detail.warnings.length }}）</h4>
            <IssueList :issues="detail.warnings" tone="warning" />
          </template>

          <h4>
            {{
              detail.currentPublishedVersion
                ? `与当前已发布版本（v${detail.currentPublishedVersion}）的差异`
                : '版本差异（首次发布的新规则包）'
            }}
          </h4>
          <DiffSummary :diff="detail.diff" />

          <div class="yaml-section">
            <div class="yaml-head">
              <h4>规则包 YAML 原文</h4>
              <div>
                <el-button v-if="yamlContent" size="small" text :icon="CopyDocument" @click="copyText(yamlContent, 'YAML 已复制')">
                  复制
                </el-button>
                <el-button size="small" plain :icon="Document" :loading="yamlLoading" data-test="toggle-yaml-btn" @click="loadYaml">
                  {{ yamlVisible ? '收起 YAML' : '查看 YAML' }}
                </el-button>
              </div>
            </div>
            <pre v-if="yamlVisible" class="yaml-code mono" data-test="submission-yaml">{{ yamlContent }}</pre>
          </div>

          <div v-if="detail.status === 'PENDING'" class="review-box">
            <template v-if="isAdmin">
              <h4>管理员审批</h4>
              <el-alert v-if="reviewError" :title="reviewError" type="error" show-icon :closable="false" class="gap" />
              <el-input
                v-model="reviewComment"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="批准时可留空或填写备注；驳回时必填驳回原因"
                data-test="review-comment-input"
              />
              <div class="review-actions">
                <el-button
                  v-if="canWithdraw(detail)"
                  plain
                  :loading="actionBusy"
                  @click="withdraw(detail.id, `${detail.conch} v${detail.version}`)"
                >
                  撤回
                </el-button>
                <span class="spacer" />
                <el-button type="danger" plain :icon="Close" :loading="actionBusy" data-test="reject-btn" @click="reject">
                  驳回
                </el-button>
                <el-button
                  type="primary"
                  :icon="Check"
                  :loading="actionBusy"
                  :disabled="detail.conchDisabled || detail.versionConflict"
                  data-test="approve-btn"
                  @click="approve"
                >
                  批准并发布
                </el-button>
              </div>
            </template>
            <template v-else-if="canWithdraw(detail)">
              <div class="review-actions">
                <span class="hm-muted">等待管理员审批中，如需修改可先撤回后重新提交。</span>
                <el-button
                  type="danger"
                  plain
                  :loading="actionBusy"
                  data-test="withdraw-drawer-btn"
                  @click="withdraw(detail.id, `${detail.conch} v${detail.version}`)"
                >
                  撤回提交
                </el-button>
              </div>
            </template>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.gap {
  margin-bottom: 14px;
}

.spacer {
  flex: 1;
}

.conch-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.small {
  font-size: 12px;
}

.reject-text {
  color: var(--hm-danger);
}

.dialog-hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
}

.note-form {
  margin-top: 14px;
}

.failure-box {
  margin-top: 12px;
}

.drawer-body h4 {
  margin: 18px 0 8px;
  font-size: 14px;
}

.drawer-badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.conch-link {
  margin-left: auto;
  font-size: 13px;
}

.pre-wrap {
  white-space: pre-wrap;
  word-break: break-word;
}

.yaml-section {
  margin-top: 14px;
}

.yaml-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.yaml-code {
  margin: 8px 0 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--hm-code-bg);
  color: var(--hm-code-text);
  font-size: 12px;
  line-height: 1.55;
  max-height: 360px;
  overflow: auto;
}

.review-box {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--hm-border);
}

.review-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
</style>
