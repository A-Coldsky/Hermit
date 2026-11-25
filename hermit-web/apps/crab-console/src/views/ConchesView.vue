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
import KindShell from '@shared/components/KindShell.vue'
import HermitMark from '@shared/components/HermitMark.vue'
import { ApiError } from '@shared/http'
import { formatTime, notifyError } from '@shared/format'
import {
  api,
  type AuthenticatorView,
  type ConchSummary,
  type LocalConchEntry,
  type LocalConchHistoryEntry,
  type LocalFileResult,
  type LocalImportConflict,
  type LocalOverrideGrant,
  type PendingLocalOverwrite,
  type ValidationIssue,
} from '../api'
import { notifySameSite, sameAuthText, sameSiteText } from '../same-site'
import LocalHistoryList from '../components/LocalHistoryList.vue'
import { formatImportIssue } from '../import-issue'
import { refreshPending } from '../status'
import { canRollback, previousBlockedNote } from '../previous'
import { disabledReasonOnly } from '../disabled'
import { updateConch } from '../update'

const router = useRouter()
const loading = ref(true)
const loadFailed = ref(false)
const conches = ref<ConchSummary[]>([])
const broken = ref<LocalConchEntry[]>([])
/** 页签：可调用的规则包，或只负责登录的授权器。 */
const tab = ref<'packages' | 'authenticators'>('packages')
const authenticators = ref<AuthenticatorView[]>([])
const deleting = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const reloading = ref(false)
const acting = ref<string | null>(null)
const pendingOverwrites = ref<PendingLocalOverwrite[]>([])
const grants = ref<LocalOverrideGrant[]>([])
const rememberChoice = reactive<Record<string, boolean>>({})
const histories = ref<Record<string, LocalConchHistoryEntry[]>>({})
const historyErrors = ref<Record<string, string>>({})
const pendingConflicts = reactive<Record<string, string>>({})
/** 磁盘已变的 409 会丢掉待确认。卡片随之消失，这句话留在列表上方。 */
const pendingDropped = ref('')

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
      `本机已有本地规则包 ${conch}。覆盖后以这次导入的文件为准，账号、凭证和配置保留。地址变化时会清空登录会话，并且可以回退。要覆盖吗？`,
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
    notifySameSite(result.conch, result)
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
    const warned = results.filter((r) => r.ok && (r.issues?.length ?? 0) > 0).length
    if (failed || warned) {
      const bits = [`${results.length} 个文件`]
      if (failed) bits.push(`${failed} 个没有加载`)
      if (warned) bits.push(`${warned} 个有校验警告`)
      showReport({ title: '重新加载本地规则包', summary: `${bits.join('，')}。`, files: results })
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

function versionLabel(version: string | null | undefined): string {
  return version ? `v${version}` : '读不出来'
}

function addedAddresses(row: PendingLocalOverwrite): string[] {
  return Array.isArray(row.addedDestinations) ? row.addedDestinations : []
}

function historyStamp(entry: LocalConchHistoryEntry): string {
  const prefix = /^(\d{17})/.exec(entry.id)
  if (prefix) return prefix[1]
  return entry.savedAt ?? ''
}

function sortHistory(rows: LocalConchHistoryEntry[]): LocalConchHistoryEntry[] {
  return [...rows].sort((a, b) => historyStamp(b).localeCompare(historyStamp(a)) || a.id.localeCompare(b.id))
}

function historyOf(conch: string): LocalConchHistoryEntry[] {
  return histories.value[conch] ?? []
}

function localNames(installed: ConchSummary[], brokenRows: LocalConchEntry[]): string[] {
  const names = new Set<string>()
  for (const row of installed) {
    if (row.source === 'LOCAL') names.add(row.conch)
  }
  for (const row of brokenRows) names.add(row.conch)
  return [...names]
}

async function loadHistories(names: string[]) {
  const entries: Record<string, LocalConchHistoryEntry[]> = {}
  const errors: Record<string, string> = {}
  await Promise.all(names.map(async (conch) => {
    try {
      const body = await api.localConchHistory(conch)
      entries[conch] = sortHistory(Array.isArray(body?.history) ? body.history : [])
    } catch (error) {
      entries[conch] = []
      errors[conch] = error instanceof Error ? error.message : '历史版本没有载入'
    }
  }))
  histories.value = entries
  historyErrors.value = errors
}

function rememberPending(rows: PendingLocalOverwrite[]) {
  const previous = new Map(pendingOverwrites.value.map((row) => [row.conch, row.sha256]))
  const still = new Set(rows.map((row) => row.conch))
  for (const row of rows) {
    if (previous.get(row.conch) !== row.sha256) {
      delete pendingConflicts[row.conch]
      rememberChoice[row.conch] = false
    } else if (rememberChoice[row.conch] === undefined) {
      rememberChoice[row.conch] = false
    }
  }
  for (const name of Object.keys(pendingConflicts)) {
    if (!still.has(name)) delete pendingConflicts[name]
  }
  for (const name of Object.keys(rememberChoice)) {
    if (!still.has(name)) delete rememberChoice[name]
  }
  pendingOverwrites.value = rows
}

async function load() {
  loading.value = true
  pendingDropped.value = ''
  try {
    const [installed, authResult, local, pendingResult, grantResult] = await Promise.all([
      api.conches(),
      api.authenticators().catch((error: unknown) => {
        notifyError(error)
        return null
      }),
      api.localConches().catch((error: unknown) => {
        notifyError(error)
        return null
      }),
      api.pendingLocalOverwrites().catch((error: unknown) => {
        notifyError(error)
        return null
      }),
      api.localOverrideGrants().catch((error: unknown) => {
        notifyError(error)
        return null
      }),
    ])
    const nextConches = installed.conches
    const nextBroken = local ? local.conches.filter((row) => row.broken) : []
    const nextPending = Array.isArray(pendingResult?.pending) ? pendingResult.pending : []
    grants.value = Array.isArray(grantResult?.grants) ? grantResult.grants : []
    await loadHistories(localNames(nextConches, nextBroken))
    conches.value = nextConches
    if (authResult) authenticators.value = authResult.authenticators ?? []
    broken.value = nextBroken
    rememberPending(nextPending)
    loadFailed.value = false
    void refreshPending()
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function confirmKey(conch: string) {
  return `confirm:${conch}`
}

function rejectKey(conch: string) {
  return `reject:${conch}`
}

function restoreKey(conch: string, id: string) {
  return `restore:${conch}:${id}`
}

function rollbackKey(conch: string) {
  return `rollback:${conch}`
}

function updateKey(conch: string) {
  return `update:${conch}`
}

function restoreLoadingId(conch: string): string | null {
  const prefix = `restore:${conch}:`
  const current = acting.value
  return current?.startsWith(prefix) ? current.slice(prefix.length) : null
}

async function confirmPending(row: PendingLocalOverwrite) {
  if (!row.sha256) return
  const key = confirmKey(row.conch)
  const conch = row.conch
  acting.value = key
  pendingConflicts[conch] = ''
  try {
    const done = await api.confirmPendingOverwrite(conch, row.sha256, rememberChoice[conch] === true)
    ElMessage.success(`已确认覆盖 ${conch} ${versionLabel(row.toVersion)}`)
    notifySameSite(conch, done)
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      const message = error.message || '确认没有完成。'
      await load()
      // 磁盘已变时服务端丢掉这条待确认；内容变了时较新的那条还在。
      if (pendingOverwrites.value.some((item) => item.conch === conch)) {
        pendingConflicts[conch] = message
      } else {
        pendingDropped.value = message
      }
      return
    }
    notifyError(error)
  } finally {
    if (acting.value === key) acting.value = null
  }
}

function grantKey(conch: string) {
  return `grant:${conch}`
}

async function revokeGrant(grant: LocalOverrideGrant) {
  const key = grantKey(grant.conch)
  acting.value = key
  try {
    await api.revokeLocalOverrideGrant(grant.conch)
    ElMessage.success(`已取消 ${grant.conch} 的自动确认`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    if (acting.value === key) acting.value = null
  }
}

async function rejectPending(row: PendingLocalOverwrite) {
  const key = rejectKey(row.conch)
  acting.value = key
  try {
    await api.rejectPendingOverwrite(row.conch)
    ElMessage.success(`已拒绝覆盖 ${row.conch}`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    if (acting.value === key) acting.value = null
  }
}

async function restoreHistory(conch: string, entry: LocalConchHistoryEntry) {
  try {
    await ElMessageBox.confirm(
      `回退 ${conch} 到 v${entry.version}。回退前会先备份当前版本。地址变化时会清空登录会话。要回退吗？`,
      `回退 ${conch}`,
      { confirmButtonText: '回退', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const key = restoreKey(conch, entry.id)
  acting.value = key
  try {
    const done = await api.restoreLocalConch(conch, entry.id)
    ElMessage.success(`已回退 ${conch} 到 v${entry.version}`)
    notifySameSite(conch, done)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    if (acting.value === key) acting.value = null
  }
}

function consumerCount(row: AuthenticatorView): number {
  return new Set(row.accounts.flatMap((a) => a.consumers.map((c) => c.conch))).size
}

function consumerNames(row: AuthenticatorView): string[] {
  return [...new Set(row.accounts.flatMap((a) => a.consumers.map((c) => c.conch)))].sort()
}

async function updateAuthenticator(row: AuthenticatorView) {
  const key = updateKey(row.conch)
  acting.value = key
  try {
    if (await updateConch({
      conch: row.conch,
      installedVersion: row.version,
      latestVersion: row.latestVersion,
      newRequiredCredentials: row.newRequiredCredentials ?? [],
    })) {
      await load()
    }
  } finally {
    if (acting.value === key) acting.value = null
  }
}

function open(row: { conch: string }) {
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

function openBroken(row: LocalConchEntry) {
  if (!row.ruleDisabled) return
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

async function rollbackListed(row: ConchSummary) {
  if (!canRollback(row)) return
  try {
    await ElMessageBox.confirm(
      `退回 v${row.previousVersion}。当前版本会留作下一次可退回的版本。`,
      `退回 ${row.conch}`,
      { confirmButtonText: '退回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const key = rollbackKey(row.conch)
  acting.value = key
  try {
    await api.rollback(row.conch)
    ElMessage.success(`已退回 v${row.previousVersion}`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    if (acting.value === key) acting.value = null
  }
}

async function updateListed(row: ConchSummary) {
  const key = updateKey(row.conch)
  acting.value = key
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
    if (acting.value === key) acting.value = null
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

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="load-failed"
      :title="conches.length || broken.length ? '规则包列表刷新失败，下面是上一次读到的内容' : '规则包列表读取失败'"
      data-test="conches-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <p v-if="pendingDropped" class="pending-conflict pending-dropped" data-test="pending-dropped" role="alert">
      {{ pendingDropped }}
    </p>

    <div v-if="grants.length" class="grant-list" data-test="override-grants">
      <article v-for="grant in grants" :key="grant.conch" class="hm-card grant" data-test="override-grant">
        <div class="grant-meta">
          <strong data-test="override-grant-conch">{{ grant.conch }}</strong>
          <span class="hm-muted" data-test="override-grant-since">{{ formatTime(grant.since) }} 起不再询问</span>
        </div>
        <el-button
          data-test="override-grant-revoke"
          :loading="acting === grantKey(grant.conch)"
          @click="revokeGrant(grant)"
        >撤销</el-button>
      </article>
    </div>

    <div v-if="pendingOverwrites.length" class="pending-list" data-test="pending-overwrites">
      <article v-for="row in pendingOverwrites" :key="row.conch" class="hm-card pending" data-test="pending-overwrite">
        <div class="hm-card-title">
          <span>待确认覆盖</span>
          <el-tag type="warning" effect="plain" size="small">需要确认</el-tag>
        </div>
        <div class="top">
          <ConchAvatar :name="row.conch" />
          <div class="name">
            <strong>{{ row.conch }}</strong>
            <span class="mono hm-muted">本地规则包</span>
          </div>
        </div>
        <div class="versions" data-test="pending-versions">
          <span>当前版本 <b class="mono">{{ versionLabel(row.fromVersion) }}</b></span>
          <span>新版本 <b class="mono">{{ versionLabel(row.toVersion) }}</b></span>
        </div>
        <div>
          <div class="address-label">新增地址</div>
          <ul v-if="addedAddresses(row).length" class="addresses">
            <li v-for="address in addedAddresses(row)" :key="address" class="mono" data-test="pending-address">{{ address }}</li>
          </ul>
          <p v-else class="hm-muted address-empty">没有新增地址</p>
          <p v-if="row.undetermined" class="pending-note" data-test="pending-undetermined">还有无法事先确定的地址。</p>
        </div>
        <p class="pending-meta">
          提交的 Key <b data-test="pending-key">{{ row.keyName || '—' }}</b>
          <span class="dot">·</span>
          <span data-test="pending-expiry">{{ formatTime(row.expiresAt) }} 到期</span>
        </p>
        <p class="pending-note">确认后才会写入。地址变化会清空这个包的登录会话，并且可以回退。</p>
        <p v-if="pendingConflicts[row.conch]" class="pending-conflict" data-test="pending-conflict" role="alert">
          {{ pendingConflicts[row.conch] }}
        </p>
        <el-checkbox
          v-model="rememberChoice[row.conch]"
          class="remember"
          data-test="pending-remember"
        >本次开发者模式内不再询问</el-checkbox>
        <div class="pending-actions">
          <el-button
            type="primary"
            data-test="pending-confirm"
            :disabled="!row.sha256"
            :loading="acting === confirmKey(row.conch)"
            @click="confirmPending(row)"
          >确认覆盖</el-button>
          <el-button
            data-test="pending-reject"
            :loading="acting === rejectKey(row.conch)"
            @click="rejectPending(row)"
          >拒绝</el-button>
        </div>
      </article>
    </div>

    <el-radio-group v-model="tab" class="tabs" data-test="kind-tabs">
      <el-radio-button value="packages"><KindShell kind="http" :size="18" /> 规则包 {{ conches.length + broken.length }}</el-radio-button>
      <el-radio-button value="authenticators">
        <KindShell kind="auth" :size="18" /> 授权器 {{ authenticators.length }}
        <span v-if="authenticators.some((a) => a.updateAvailable)" class="tab-dot" data-test="authenticator-update-dot" />
      </el-radio-button>
    </el-radio-group>

    <template v-if="tab === 'authenticators'">
      <p class="hm-muted tab-hint">
        授权器只负责登录，没有能力，不能被调用。引用它的业务包把账号绑到授权器账号上，共用一份登录；账号之间的绑定在「授权与身份」里看。
      </p>
      <div v-if="authenticators.length" class="cards" data-test="authenticator-cards">
        <div
          v-for="row in authenticators"
          :key="`${row.source}/${row.conch}`"
          class="hm-card conch clickable"
          role="link"
          tabindex="0"
          data-test="authenticator-card"
          @click="open(row)"
          @keyup.enter="open(row)"
        >
          <div class="top">
            <ConchAvatar :name="row.conch" :title="row.title" kind="auth" />
            <div class="name">
              <strong>
                {{ row.title || row.conch }}
                <el-tag v-if="row.source === 'LOCAL'" type="info" effect="plain" size="small" class="local">本地</el-tag>
                <el-tag v-if="row.updateAvailable" type="warning" effect="plain" size="small" class="local mono">有新版本 v{{ row.latestVersion }}</el-tag>
              </strong>
              <span class="mono hm-muted">{{ row.conch }} · v{{ row.version }}</span>
            </div>
          </div>
          <div class="status" :class="row.accounts.length ? 'ok' : 'idle'">
            <el-icon><component :is="row.accounts.length ? CircleCheckFilled : User" /></el-icon>
            <span v-if="!row.accounts.length">未建账号：先新建一个账号并填凭证</span>
            <span v-else-if="consumerCount(row)" data-test="authenticator-used-by">被 {{ consumerNames(row).join('、') }} 使用</span>
            <span v-else>还没有业务包的账号绑到它上面</span>
          </div>
          <p v-if="row.updateAvailable" class="update-note">
            可以更新到 v{{ row.latestVersion }}<template v-if="row.newRequiredCredentials?.length">，新版本需要凭证 {{ row.newRequiredCredentials.join('、') }}</template>。更新后绑到它的业务包都用新版本登录。
          </p>
          <div class="foot hm-muted">
            <span><b class="num">{{ row.accounts.length }}</b> 个账号</span>
            <span><b class="num">{{ consumerCount(row) }}</b> 个业务包在用</span>
            <span class="spacer" />
            <el-button
              v-if="row.updateAvailable"
              link
              type="primary"
              class="card-action"
              data-test="authenticator-update"
              :loading="acting === updateKey(row.conch)"
              @click.stop="updateAuthenticator(row)"
              @keyup.enter.stop
            >更新到 v{{ row.latestVersion }}</el-button>
            <span class="manage">管理<el-icon><ArrowRight /></el-icon></span>
          </div>
        </div>
      </div>
      <div v-else-if="!loading" class="hm-card empty" data-test="authenticators-empty">
        <KindShell kind="auth" :size="56" class="empty-mark" />
        <span>本机还没有授权器。到<router-link to="/catalog">可订阅规则包</router-link>的「授权器」页签订阅，或导入本地授权器</span>
      </div>
    </template>

    <template v-else>
    <div v-if="loading && !conches.length && !broken.length" class="cards">
      <div v-for="n in 2" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
    </div>
    <div v-else-if="conches.length || broken.length" class="cards">
      <div
        v-for="row in conches"
        :key="row.conch"
        class="hm-card conch clickable"
        :class="{ warn: row.needsConfiguration && !row.ruleDisabled, broken: row.ruleDisabled }"
        role="link"
        tabindex="0"
        @click="open(row)"
        @keyup.enter="open(row)"
      >
        <div class="top">
          <ConchAvatar :name="row.conch" :title="row.title" :kind="row.driver" />
          <div class="name">
            <strong>
              {{ row.title || row.conch }}
              <el-tag v-if="row.source === 'LOCAL'" type="info" effect="plain" size="small" class="local">本地</el-tag>
              <el-tag v-if="row.localVersion && row.reefVersion" type="info" effect="plain" size="small" class="local" data-test="two-sources-tag">{{ row.source === 'LOCAL' ? '另有同名正式包' : '另有同名本地包' }}</el-tag>
              <el-tag v-if="row.ruleDisabled" type="danger" effect="plain" size="small" class="local" data-test="rule-disabled-tag">已停用</el-tag>
              <el-tag v-if="row.switchedOff" type="danger" effect="plain" size="small" class="local" data-test="switched-off-tag">本机已停用</el-tag>
              <el-tooltip v-if="row.sameSite?.length" :content="`${sameSiteText(row.sameSite)}`" placement="top">
                <el-tag type="info" effect="plain" size="small" class="local" data-test="same-site-tag">同网站 {{ row.sameSite.length }} 个包</el-tag>
              </el-tooltip>
              <el-tooltip v-if="row.sameAuth?.length" :content="`${sameAuthText(row.sameAuth)}`" placement="top">
                <el-tag type="info" effect="plain" size="small" class="local" data-test="same-auth-tag">共用认证 {{ row.sameAuth.length }} 个包</el-tag>
              </el-tooltip>
              <el-tag v-if="row.reefDisabled" type="warning" effect="plain" size="small" class="local reef-off">Reef 已停用</el-tag>
              <el-tag v-if="row.updateAvailable" type="warning" effect="plain" size="small" class="local mono">有新版本 v{{ row.latestVersion }}</el-tag>
            </strong>
            <span class="mono hm-muted">{{ row.conch }} · v{{ row.version }}</span>
          </div>
        </div>
        <div class="status" :class="row.ruleDisabled || row.switchedOff ? 'bad' : row.needsConfiguration ? 'warn' : row.accountCount ? 'ok' : 'idle'">
          <el-icon><component :is="row.ruleDisabled || row.switchedOff ? CircleCloseFilled : row.needsConfiguration ? WarningFilled : row.accountCount ? CircleCheckFilled : User" /></el-icon>
          <span v-if="row.ruleDisabled">这版规则暂时不能调用</span>
          <span v-else-if="row.switchedOff" data-test="switched-off-status">已停用：不能调用，进入详情可以重新启用</span>
          <span v-else-if="row.loginPaused">已暂停：有账号自动登录已暂停，进入详情查看恢复方法</span>
          <span v-else-if="!row.accountCount">未建账号：先新建一个账号</span>
          <span v-else-if="row.needsConfiguration">待配置：还缺凭证或配置项</span>
          <span v-else>可用：可以通过 OpenAPI 和 MCP 调用</span>
        </div>
        <p v-if="row.ruleDisabled && disabledReasonOnly(row.message)" class="broken-reason" data-test="rule-message">{{ disabledReasonOnly(row.message) }}</p>
        <p v-if="row.fixHint" class="broken-reason" data-test="fix-hint">{{ row.fixHint }}</p>
        <p v-if="previousBlockedNote(row)" class="broken-reason" data-test="previous-note">{{ previousBlockedNote(row) }}</p>
        <p v-if="row.reefDisabled" class="reef-off-note">Reef 已停用：本机版本仍可调用，但不会再更新</p>
        <p v-else-if="row.updateAvailable" class="update-note">
          可以更新到 v{{ row.latestVersion }}<template v-if="row.newRequiredCredentials.length">，新版本需要凭证 {{ row.newRequiredCredentials.join('、') }}</template>。进入详情更新
        </p>
        <LocalHistoryList
          v-if="row.source === 'LOCAL'"
          :entries="historyOf(row.conch)"
          :error="historyErrors[row.conch] ?? null"
          :loading-id="restoreLoadingId(row.conch)"
          @restore="restoreHistory(row.conch, $event)"
        />
        <div class="foot hm-muted">
          <span v-if="!(row.ruleDisabled && row.capabilityCount === 0)"><b class="num">{{ row.capabilityCount }}</b> 个能力</span>
          <span><b class="num">{{ row.accountCount }}</b> 个账号</span>
          <span class="spacer" />
          <el-button
            v-if="canRollback(row)"
            link
            type="primary"
            class="card-action"
            data-test="rollback"
            :loading="acting === rollbackKey(row.conch)"
            @click.stop="rollbackListed(row)"
            @keyup.enter.stop
          >退回 v{{ row.previousVersion }}</el-button>
          <el-button
            v-if="row.updateAvailable"
            link
            type="primary"
            class="card-action"
            :loading="acting === updateKey(row.conch)"
            @click.stop="updateListed(row)"
            @keyup.enter.stop
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
          <span v-if="row.ruleDisabled">这版规则暂时不能调用</span>
          <span v-else>未载入，不能调用。修好文件后重新导入覆盖，或先删除再导入</span>
        </div>
        <p v-if="row.ruleDisabled ? disabledReasonOnly(row.reason) : row.reason" class="broken-reason" data-test="broken-reason">
          {{ row.ruleDisabled ? disabledReasonOnly(row.reason) : row.reason }}
        </p>
        <p v-if="row.fixHint" class="broken-reason" data-test="fix-hint">{{ row.fixHint }}</p>
        <LocalHistoryList
          :entries="historyOf(row.conch)"
          :error="historyErrors[row.conch] ?? null"
          :loading-id="restoreLoadingId(row.conch)"
          @restore="restoreHistory(row.conch, $event)"
        />
        <div class="foot hm-muted">
          <span class="spacer" />
          <el-button
            type="danger"
            plain
            size="small"
            :icon="Delete"
            :loading="deleting === row.conch"
            @click.stop="removeBroken(row)"
            @keyup.enter.stop
          >删除</el-button>
        </div>
      </div>
    </div>
    <div v-else-if="!loading && !loadFailed && !pendingOverwrites.length" class="hm-card empty">
      <HermitMark variant="crab" :size="56" :tile="false" class="empty-mark" />
      <span>还没有规则包。到<router-link to="/catalog">可订阅规则包</router-link>订阅 Reef 上的规则包，或导入本地规则包</span>
    </div>
    </template>

    <el-dialog v-model="report.open" :title="report.title" width="600px">
      <p v-if="report.summary" class="summary">{{ report.summary }}</p>
      <h4 v-if="report.errors.length" class="issue-title">错误</h4>
      <ul v-if="report.errors.length" class="issues bad">
        <li v-for="(view, i) in shown(report.errors)" :key="`e${i}`" data-test="import-error">
          <el-icon class="mark"><CircleCloseFilled /></el-icon>
          <span class="code">{{ view.code }}</span>
          <span v-if="view.label" class="gloss">{{ view.label }}</span>
          <span v-if="view.location" class="where">{{ view.location }}</span>
          <span class="reason">{{ view.reason }}</span>
        </li>
      </ul>
      <h4 v-if="report.warnings.length" class="issue-title">警告</h4>
      <p v-if="report.warnings.length && !report.errors.length && !report.files.length" class="hm-muted warn-note">警告不阻止保存。</p>
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
            <template v-if="row.ok">
              <span class="ok">已加载 {{ row.conch }} v{{ row.version }}</span>
              <ul v-if="row.issues?.length" class="issues warn file-warnings">
                <li v-for="(view, i) in shown(row.issues)" :key="`${row.file}-${i}`" data-test="file-warning">
                  <el-icon class="mark"><WarningFilled /></el-icon>
                  <span class="code">{{ view.code }}</span>
                  <span v-if="view.label" class="gloss">{{ view.label }}</span>
                  <span v-if="view.location" class="where">{{ view.location }}</span>
                  <span class="reason">{{ view.reason }}</span>
                </li>
              </ul>
            </template>
            <span v-else class="err">{{ (row.issues ?? []).map((i: ValidationIssue) => i.message).join('；') || '没有加载' }}</span>
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
.load-failed {
  margin-bottom: 16px;
}

/* 卡片上的「退回」「更新到」和正文同一字号。 */
.card-action {
  font-size: 12.5px;
}

.tabs {
  margin-bottom: 14px;
}

.tabs :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-warning);
}

.tab-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(340px, 100%), 1fr));
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
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 44px 20px;
  text-align: center;
  color: var(--hm-muted);
}

.file {
  display: none;
}

.grant-list,
.pending-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 16px;
}

.grant {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.grant-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.remember {
  align-self: flex-start;
  margin: 0;
}

.pending-list > .hm-card + .hm-card {
  margin-top: 0;
}

.pending {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border-color: var(--el-color-warning-light-5);
}

.versions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 13px;
}

.versions b {
  font-weight: 600;
}

.address-label {
  margin-bottom: 6px;
  font-size: 12.5px;
  font-weight: 600;
}

.address-empty {
  margin: 0;
  font-size: 12.5px;
}

.addresses {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.addresses li {
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--hm-subtle);
  word-break: break-all;
}

.pending-meta {
  margin: 0;
  font-size: 13px;
}

.pending-meta b {
  color: var(--hm-text);
  font-weight: 600;
}

.dot {
  margin: 0 6px;
  color: var(--hm-muted);
}

.pending-note {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--hm-muted);
}

.pending-conflict {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hm-danger);
}

.pending-dropped {
  margin-bottom: 16px;
}

.pending-actions {
  display: flex;
  gap: 8px;
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

.issue-title {
  margin: 4px 0 8px;
  font-size: 14px;
}

.warn-note {
  margin: -4px 0 8px;
}

.file-warnings {
  margin-top: 8px;
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
