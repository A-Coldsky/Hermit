<script setup lang="ts">
import {
  ArrowRight,
  CircleCheckFilled,
  CircleCloseFilled,
  Delete,
  FolderOpened,
  Refresh,
  Upload,
  User,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import {
  api,
  type ConchSummary,
  type LocalConchEntry,
  type LocalFileResult,
  type LocalImportConflict,
  type ValidationIssue,
} from '../api'
import { formatImportIssue } from '../import-issue'
import { refreshPending } from '../status'
import { updateConch } from '../update'

const router = useRouter()
const loading = ref(true)
const conches = ref<ConchSummary[]>([])
const broken = ref<LocalConchEntry[]>([])
const deleting = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const reloading = ref(false)

const report = reactive({
  open: false,
  title: '',
  summary: '',
  errors: [] as ValidationIssue[],
  warnings: [] as ValidationIssue[],
  files: [] as LocalFileResult[],
})

function shown(issues: ValidationIssue[]) {
  return issues.map(formatImportIssue)
}

function showReport(next: Partial<Omit<typeof report, 'open'>>) {
  Object.assign(report, { title: '', summary: '', errors: [], warnings: [], files: [] }, next, { open: true })
}

function issuesOf(data: unknown): { errors: ValidationIssue[]; warnings: ValidationIssue[] } | null {
  if (!data || typeof data !== 'object') return null
  const { errors, warnings } = data as { errors?: ValidationIssue[]; warnings?: ValidationIssue[] }
  if (!Array.isArray(errors) && !Array.isArray(warnings)) return null
  return { errors: errors ?? [], warnings: warnings ?? [] }
}

function pickFile() {
  fileInput.value?.click()
}

async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    await upload(file, false)
  } finally {
    importing.value = false
  }
}

function overwriteConflict(error: unknown): LocalImportConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409 || error.code !== 'CONFLICT') return null
  const data = error.data as Partial<LocalImportConflict> | null
  return data && data.confirm === true && typeof data.conch === 'string' ? (data as LocalImportConflict) : null
}

async function confirmOverwrite(conch: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `本机已有本地规则包 ${conch}。覆盖后以这次导入的文件为准，账号、凭证和配置保留。要覆盖吗？`,
      '已有同名本地规则包',
      { confirmButtonText: '覆盖', cancelButtonText: '取消', type: 'warning' },
    )
    return true
  } catch {
    return false
  }
}

async function upload(file: File, confirm: boolean) {
  try {
    const result = await api.importLocalConch(file, confirm)
    const verb = result.replaced ? '已覆盖' : '已导入'
    if (result.warnings.length) {
      showReport({
        title: `${verb}本地规则包 ${result.conch}`,
        summary: `v${result.version} 已可调用，有 ${result.warnings.length} 条警告。`,
        warnings: result.warnings,
      })
    } else {
      ElMessage.success(`${verb}本地规则包 ${result.conch} v${result.version}`)
    }
    await load()
  } catch (error) {
    const conflict = confirm ? null : overwriteConflict(error)
    if (conflict) {
      if (await confirmOverwrite(conflict.conch)) await upload(file, true)
      return
    }
    const issues = error instanceof ApiError ? issuesOf(error.data) : null
    if (issues) {
      showReport({ title: '导入失败', summary: error instanceof Error ? error.message : '', ...issues })
    } else {
      notifyError(error)
    }
  }
}

async function removeBroken(row: LocalConchEntry) {
  try {
    await ElMessageBox.confirm(
      `删除后 ${row.file} 会从本机移除。账号、凭证和配置会保留，重新导入同名规则包后可以直接使用。`,
      `删除本地规则包 ${row.conch}`,
      { confirmButtonText: '删除', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  deleting.value = row.conch
  try {
    await api.deleteLocalConch(row.conch)
    ElMessage.success(`已删除本地规则包 ${row.conch}，可以重新导入`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    deleting.value = null
  }
}

async function reloadLocal() {
  reloading.value = true
  try {
    const { results } = await api.reloadLocalConches()
    const failed = results.filter((r) => !r.ok).length
    if (failed) {
      showReport({ title: '重新加载本地规则包', summary: `${results.length} 个文件，${failed} 个没有加载。`, files: results })
    } else {
      ElMessage.success(results.length ? `已重新加载 ${results.length} 个本地规则包` : '本地规则包目录是空的')
    }
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    reloading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const [installed, local] = await Promise.all([
      api.conches(),
      api.localConches().catch((error: unknown) => {
        notifyError(error)
        return null
      }),
    ])
    conches.value = installed.conches
    broken.value = local ? local.conches.filter((row) => row.broken) : []
    void refreshPending()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function open(row: ConchSummary) {
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

function openBroken(row: LocalConchEntry) {
  if (!row.ruleDisabled) return
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

const acting = ref<string | null>(null)

async function rollbackListed(row: ConchSummary) {
  if (!row.previousVersion || row.previousDisabled) return
  try {
    await ElMessageBox.confirm(
      `退回 v${row.previousVersion}。当前版本会留作下一次可退回的版本。`,
      `退回 ${row.conch}`,
      { confirmButtonText: '退回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  acting.value = row.conch
  try {
    await api.rollback(row.conch)
    ElMessage.success(`已退回 v${row.previousVersion}`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    acting.value = null
  }
}

async function updateListed(row: ConchSummary) {
  acting.value = row.conch
  try {
    if (await updateConch({
      conch: row.conch,
      installedVersion: row.version,
      latestVersion: row.latestVersion,
      newRequiredCredentials: row.newRequiredCredentials,
    })) {
      await load()
    }
  } finally {
    acting.value = null
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="规则包与账号" description="已安装到本机的规则包。为每个规则包建账号、填凭证和配置后即可调用">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="FolderOpened" :loading="reloading" @click="reloadLocal">重新加载本地目录</el-button>
      <el-button type="primary" :icon="Upload" :loading="importing" @click="pickFile">导入本地规则包</el-button>
      <input ref="fileInput" type="file" accept=".yaml,.yml" class="file" data-test="local-file" @change="importFile" />
    </PageHeader>

    <div v-if="loading && !conches.length && !broken.length" class="cards">
      <div v-for="n in 2" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
    </div>
    <div v-else-if="conches.length || broken.length" class="cards">
      <div
        v-for="row in conches"
        :key="row.conch"
        class="hm-card conch clickable"
        :class="{ warn: row.needsConfiguration }"
        role="link"
        tabindex="0"
        @click="open(row)"
        @keyup.enter="open(row)"
      >
        <div class="top">
          <ConchAvatar :name="row.conch" :title="row.title" />
          <div class="name">
            <strong>
              {{ row.title || row.conch }}
              <el-tag v-if="row.source === 'LOCAL'" type="info" effect="plain" size="small" class="local">本地</el-tag>
              <el-tag v-if="row.ruleDisabled" type="danger" effect="plain" size="small" class="local" data-test="rule-disabled-tag">已停用</el-tag>
              <el-tag v-if="row.reefDisabled" type="warning" effect="plain" size="small" class="local reef-off">Reef 已停用</el-tag>
              <el-tag v-if="row.updateAvailable" type="warning" effect="plain" size="small" class="local mono">有新版本 v{{ row.latestVersion }}</el-tag>
            </strong>
            <span class="mono hm-muted">{{ row.conch }} · v{{ row.version }}</span>
          </div>
        </div>
        <div class="status" :class="row.needsConfiguration ? 'warn' : row.accountCount ? 'ok' : 'idle'">
          <el-icon><component :is="row.needsConfiguration ? WarningFilled : row.accountCount ? CircleCheckFilled : User" /></el-icon>
          <span v-if="row.ruleDisabled">已停用</span>
          <span v-else-if="row.loginPaused">已暂停：有账号自动登录已暂停，进入详情查看恢复方法</span>
          <span v-else-if="!row.accountCount">未建账号：先新建一个账号</span>
          <span v-else-if="row.needsConfiguration">待配置：还缺凭证或配置项</span>
          <span v-else>可用：可以通过 OpenAPI 和 MCP 调用</span>
        </div>
        <p v-if="row.fixHint" class="broken-reason" data-test="fix-hint">{{ row.fixHint }}</p>
        <p v-if="row.previousDisabled && row.previousVersion" class="broken-reason">上一版 v{{ row.previousVersion }} 也已停用</p>
        <p v-if="row.reefDisabled" class="reef-off-note">Reef 已停用：本机版本仍可调用，但不会再更新</p>
        <p v-else-if="row.updateAvailable" class="update-note">
          可以更新到 v{{ row.latestVersion }}<template v-if="row.newRequiredCredentials.length">，新版本需要凭证 {{ row.newRequiredCredentials.join('、') }}</template>。进入详情更新
        </p>
        <div class="foot hm-muted">
          <span><b class="num">{{ row.capabilityCount }}</b> 个能力</span>
          <span><b class="num">{{ row.accountCount }}</b> 个账号</span>
          <span class="spacer" />
          <el-button
            v-if="row.previousVersion && !row.previousDisabled"
            link
            type="primary"
            :loading="acting === row.conch"
            @click.stop="rollbackListed(row)"
          >退回 v{{ row.previousVersion }}</el-button>
          <el-button
            v-if="row.updateAvailable"
            link
            type="primary"
            :loading="acting === row.conch"
            @click.stop="updateListed(row)"
          >更新到 v{{ row.latestVersion }}</el-button>
          <span class="manage">管理<el-icon><ArrowRight /></el-icon></span>
        </div>
      </div>
      <div
        v-for="row in broken"
        :key="`broken-${row.conch}`"
        class="hm-card conch broken"
        :class="{ clickable: row.ruleDisabled }"
        data-test="broken-conch"
        @click="openBroken(row)"
      >
        <div class="top">
          <ConchAvatar :name="row.conch" />
          <div class="name">
            <strong>
              {{ row.conch }}
              <el-tag type="info" effect="plain" size="small" class="local">本地</el-tag>
              <el-tag type="danger" effect="plain" size="small" class="local" data-test="broken-tag">{{ row.ruleDisabled ? '已停用' : '文件损坏，无法加载' }}</el-tag>
            </strong>
            <span class="mono hm-muted">{{ row.file }}</span>
          </div>
        </div>
        <div class="status bad">
          <el-icon><CircleCloseFilled /></el-icon>
          <span v-if="row.ruleDisabled">已停用，这版规则暂时不能调用</span>
          <span v-else>未载入，不能调用。修好文件后重新导入覆盖，或先删除再导入</span>
        </div>
        <p v-if="row.reason" class="broken-reason" data-test="broken-reason">{{ row.reason }}</p>
        <p v-if="row.fixHint" class="broken-reason" data-test="fix-hint">{{ row.fixHint }}</p>
        <div class="foot hm-muted">
          <span class="spacer" />
          <el-button
            type="danger"
            plain
            size="small"
            :icon="Delete"
            :loading="deleting === row.conch"
            @click.stop="removeBroken(row)"
          >删除</el-button>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="hm-card empty">
      还没有规则包。到<router-link to="/catalog">可订阅规则包</router-link>订阅 Reef 上的规则包，或导入本地规则包
    </div>

    <el-dialog v-model="report.open" :title="report.title" width="600px">
      <p v-if="report.summary" class="summary">{{ report.summary }}</p>
      <ul v-if="report.errors.length" class="issues bad">
        <li v-for="(view, i) in shown(report.errors)" :key="`e${i}`" data-test="import-error">
          <el-icon class="mark"><CircleCloseFilled /></el-icon>
          <span class="code">{{ view.code }}</span>
          <span v-if="view.label" class="gloss">{{ view.label }}</span>
          <span v-if="view.location" class="where">{{ view.location }}</span>
          <span class="reason">{{ view.reason }}</span>
        </li>
      </ul>
      <ul v-if="report.warnings.length" class="issues warn">
        <li v-for="(view, i) in shown(report.warnings)" :key="`w${i}`" data-test="import-warning">
          <el-icon class="mark"><WarningFilled /></el-icon>
          <span class="code">{{ view.code }}</span>
          <span v-if="view.label" class="gloss">{{ view.label }}</span>
          <span v-if="view.location" class="where">{{ view.location }}</span>
          <span class="reason">{{ view.reason }}</span>
        </li>
      </ul>
      <el-table v-if="report.files.length" :data="report.files" size="small">
        <el-table-column label="文件" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.file }}</span></template>
        </el-table-column>
        <el-table-column label="结果" min-width="260">
          <template #default="{ row }">
            <span v-if="row.ok" class="ok">已加载 {{ row.conch }} v{{ row.version }}</span>
            <span v-else class="err">{{ row.issues.map((i: ValidationIssue) => i.message).join('；') || '没有加载' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="report.open = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.conch {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.name strong {
  font-size: 15px;
}

.name .mono {
  font-size: 12px;
}

.status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13px;
}

.status.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
}

.status.warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}

.status.bad {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger-dark-2);
}

.broken {
  border-color: var(--el-color-danger-light-7);
}

.broken-reason {
  margin: -4px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--hm-danger);
  word-break: break-all;
}

.status.idle {
  background: var(--hm-subtle);
  color: var(--hm-muted);
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12.5px;
}

.foot b {
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.manage {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--el-color-primary);
  font-weight: 500;
}

.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--hm-muted);
}

.file {
  display: none;
}

.local {
  margin-left: 6px;
  vertical-align: 2px;
}

.reef-off-note,
.update-note {
  margin: -4px 0 0;
  font-size: 12.5px;
  color: var(--hm-warning);
}

.summary {
  margin: 0 0 12px;
}

.issues {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.issues li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  padding: 8px 12px;
  border-radius: 8px;
  line-height: 1.55;
  border-left: 3px solid transparent;
}

.issues .mark {
  font-size: 16px;
}

.issues .code,
.issues .where {
  font-family: var(--hm-mono);
  font-size: 12px;
}

.issues .code {
  font-weight: 600;
}

.issues .gloss {
  font-size: 13px;
  font-weight: 600;
}

.issues .reason {
  flex: 1 0 100%;
  font-size: 13px;
}

.issues.bad li {
  color: var(--hm-danger);
  background: var(--el-color-danger-light-9);
  border-left-color: var(--hm-danger);
}

.issues.warn li {
  color: var(--hm-warning);
  background: var(--el-color-warning-light-9);
  border-left-color: var(--hm-warning);
}

.err {
  color: var(--hm-danger);
}

.ok {
  color: var(--el-color-success-dark-2);
}
</style>
