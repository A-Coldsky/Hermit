<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import HermitDuet from '@shared/components/HermitDuet.vue'
import KindBadge from '@shared/components/KindBadge.vue'
import TimeText from '@shared/components/TimeText.vue'
import { notifyError } from '@shared/format'
import { useNarrow } from '@shared/narrow'
import { api, type Catalog, type CatalogConch } from '../api'
import { notifySameSite } from '../same-site'
import { outcomeLabels, syncReasonLabels } from '../labels'
import { refreshPending } from '../status'
import ShellSwapLoader from '../components/ShellSwapLoader.vue'
import { subscribeWithAuthenticatorMessage, unsubscribeAuthenticatorMessage, unsubscribeMessage } from '../copy'
import { repairConch, updateConch } from '../update'

/** 手机上不固定操作列：固定列会盖住大半张表，前面几列读不到。 */
const narrow = useNarrow()
const router = useRouter()
const loading = ref(true)
const loadFailed = ref(false)
const syncing = ref(false)
const busy = ref<string | null>(null)
/** 正在订阅安装的那一行，用来显示换壳动画。 */
const installing = ref<string | null>(null)
const catalog = ref<Catalog | null>(null)
/** 同步标为需要修复、但目录还没有 repairRequired 的包（例如指针丢失）。 */
const repairs = ref(new Map<string, string | null>())

function needsRepair(row: CatalogConch): boolean {
  if (row.ruleDisabled) return false
  return row.repairRequired || repairs.value.has(row.conch)
}

function repairReason(row: CatalogConch): string | null {
  return row.repairReason ?? repairs.value.get(row.conch) ?? null
}

/** 页签：可调用的规则包，或只负责登录的授权器。 */
const tab = ref<'packages' | 'authenticators'>('packages')
const all = computed(() => catalog.value?.conches ?? [])
const isAuthenticator = (row: CatalogConch) => row.driver === 'auth'
const packages = computed(() => all.value.filter((r) => !isAuthenticator(r)))
const authenticators = computed(() => all.value.filter(isAuthenticator))
const rows = computed(() => (tab.value === 'authenticators' ? authenticators.value : packages.value))
const subscribedCount = computed(() => rows.value.filter((r) => r.subscribed).length)

/** 这个包引用的授权器在目录里的那一行；不引用授权器、或目录里没有它时为 null。 */
function authenticatorOf(row: CatalogConch): CatalogConch | null {
  return row.authUse ? authenticators.value.find((r) => r.conch === row.authUse) ?? null : null
}

/** 本机现在生效的授权器（正式的和本地的都算）。 */
const effective = ref<Set<string>>(new Set())

/** 引用的授权器本机还没有：没订阅，也没有同名的授权器（包括本地包）在生效。 */
function missingAuthenticator(row: CatalogConch): CatalogConch | null {
  const target = authenticatorOf(row)
  return target && !target.subscribed && !target.installed && !target.localConflict && !effective.value.has(target.conch)
    ? target
    : null
}

function consumersOf(row: CatalogConch): string[] {
  return packages.value.filter((r) => r.authUse === row.conch).map((r) => r.conch)
}

function showAuthenticator() {
  tab.value = 'authenticators'
}

async function load() {
  loading.value = true
  try {
    const [result, status, local] = await Promise.all([
      api.catalog(),
      api.syncStatus().catch(() => null),
      api.authenticators().catch(() => null),
    ])
    catalog.value = result
    effective.value = new Set((local?.authenticators ?? []).map((a) => a.conch))
    repairs.value = new Map(
      (status?.report?.conches ?? []).filter((s) => s.kind === 'REPAIR_REQUIRED').map((s) => [s.conch, s.reason] as const),
    )
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function refreshFromReef() {
  syncing.value = true
  try {
    await api.sync()
    await load()
    void refreshPending()
  } catch (error) {
    notifyError(error)
  } finally {
    syncing.value = false
  }
}

async function subscribe(row: CatalogConch) {
  const needed = missingAuthenticator(row)
  if (needed) {
    try {
      await ElMessageBox.confirm(
        subscribeWithAuthenticatorMessage(row.conch, needed.conch),
        `一起订阅授权器 ${needed.title || needed.conch}`,
        { confirmButtonText: '一起订阅', cancelButtonText: '先不订阅', type: 'info' },
      )
    } catch {
      return
    }
  }
  busy.value = row.conch
  installing.value = row.conch
  try {
    if (needed) {
      const first = await api.subscribe(needed.conch)
      if (!first.subscribed) {
        ElMessage.warning(`没有订阅授权器 ${needed.conch}：Reef 上当前不能订阅它，${row.conch} 也没有订阅`)
        await load()
        return
      }
      const firstKind = first.outcome?.kind
      if (firstKind !== 'INSTALLED' && firstKind !== 'SKIPPED_SAME') {
        const why = firstKind ? outcomeLabels[firstKind] : null
        ElMessage.warning(
          `已订阅授权器 ${needed.conch}，但没有安装到本机${why ? `（${why}）` : ''}；${row.conch} 没有订阅，授权器装好后再订阅它`,
        )
        await load()
        return
      }
    }
    const result = await api.subscribe(row.conch)
    const kind = result.outcome?.kind
    const label = kind ? outcomeLabels[kind] : null
    notifySameSite(row.conch, result)
    if (!result.subscribed) {
      ElMessage.warning(`没有订阅 ${row.conch}：Reef 上当前不能订阅这个规则包`)
    } else if (kind === 'INSTALLED' || kind === 'SKIPPED_SAME') {
      ElMessage.success(`已订阅 ${row.conch}，已安装到本机`)
    } else if (!kind) {
      ElMessage.warning(`已订阅 ${row.conch}，但这次没有安装`)
    } else {
      const rawReason = result.outcome?.reason
      const reason = rawReason ? `：${syncReasonLabels[rawReason] ?? rawReason}` : ''
      ElMessage.warning(`已订阅 ${row.conch}，但这次没有安装（${label?.text ?? kind}${reason}）`)
    }
    await load()
    void refreshPending()
  } catch (error) {
    notifyError(error)
    await load()
  } finally {
    busy.value = null
    installing.value = null
  }
}

/** 绑在这个授权器上的业务包账号。读不到时当作没有，不拦着退订。 */
async function boundAccounts(conch: string): Promise<string[]> {
  try {
    const found = (await api.authenticators()).authenticators.find((a) => a.conch === conch && a.source !== 'LOCAL')
    return (found?.accounts ?? []).flatMap((a) => a.consumers.map((c) => `${c.conch}/${c.account}`))
  } catch {
    return []
  }
}

async function unsubscribe(row: CatalogConch) {
  const message = isAuthenticator(row)
    ? unsubscribeAuthenticatorMessage(row.conch, await boundAccounts(row.conch))
    : unsubscribeMessage(row.conch)
  try {
    await ElMessageBox.confirm(
      message,
      `取消订阅 ${row.title || row.conch}`,
      { confirmButtonText: '取消订阅', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  busy.value = row.conch
  try {
    await api.unsubscribe(row.conch)
    ElMessage.success(`已取消订阅 ${row.conch}`)
    await load()
    void refreshPending()
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}

async function update(row: CatalogConch) {
  busy.value = row.conch
  try {
    if (await updateConch(row)) void refreshPending()
    await load()
  } finally {
    busy.value = null
  }
}

async function repair(row: CatalogConch) {
  busy.value = row.conch
  try {
    if (await repairConch(row.conch, repairReason(row))) void refreshPending()
    await load()
  } finally {
    busy.value = null
  }
}

function open(row: CatalogConch) {
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="可订阅规则包" description="Reef 上已发布的规则包和授权器。订阅后本机才会下载安装；取消订阅会卸载，但保留账号和凭证">
      <el-button :icon="Refresh" :loading="syncing" :disabled="catalog?.configured === false" @click="refreshFromReef">从 Reef 刷新</el-button>
    </PageHeader>

    <el-result
      v-if="catalog && !catalog.configured"
      title="还没有连接 Reef"
      sub-title="在「连接 Reef」导入初始化 JSON 后才能看到可订阅的规则包。本地规则包不受影响"
    >
      <template #icon>
        <HermitDuet variant="scene" :size="96" status="disconnected" />
      </template>
      <template #extra>
        <el-button type="primary" @click="router.push('/reef')">连接 Reef</el-button>
      </template>
    </el-result>

    <div v-else class="hm-card">
      <el-alert
        v-if="loadFailed"
        type="error"
        show-icon
        :closable="false"
        class="load-failed"
        :title="catalog ? '目录刷新失败，下面是上一次读到的内容' : '目录读取失败'"
        data-test="catalog-load-failed"
      >
        <el-button size="small" :loading="loading" @click="load">重试</el-button>
      </el-alert>
      <el-radio-group v-if="catalog" v-model="tab" class="tabs" data-test="kind-tabs">
        <el-radio-button value="packages">规则包 {{ packages.length }}</el-radio-button>
        <el-radio-button value="authenticators">授权器 {{ authenticators.length }}</el-radio-button>
      </el-radio-group>
      <p v-if="catalog && tab === 'authenticators'" class="hm-muted tab-hint">
        授权器只负责登录，没有能力，不能被调用。引用它的业务包把账号绑到授权器账号上，共用一份登录。
      </p>
      <div v-if="catalog" class="meta hm-muted">
        <span>共 <b class="num">{{ rows.length }}</b> 个，已订阅 <b class="num">{{ subscribedCount }}</b> 个</span>
        <span v-if="catalog.syncedAt">目录更新于 <TimeText :value="catalog.syncedAt" /></span>
        <span v-else>还没有从 Reef 取到目录</span>
      </div>
      <ShellSwapLoader v-if="syncing" label="正在从 Reef 拉取目录…" class="swap" />
      <p class="hm-scroll-hint">表格可以左右滑动，「订阅」在最右侧。</p>
      <el-table v-loading="loading" :data="rows">
        <template #empty>
          {{
            loading
              ? ''
              : loadFailed
                ? '没有读到可订阅目录'
                : catalog?.syncedAt
                  ? tab === 'authenticators' ? 'Reef 上还没有已发布的授权器' : 'Reef 上还没有已发布的规则包'
                  : '点「从 Reef 刷新」获取可订阅的规则包'
          }}
        </template>
        <el-table-column :label="tab === 'authenticators' ? '授权器' : '规则包'" min-width="260">
          <template #default="{ row }">
            <div class="name">
              <ConchAvatar :name="row.conch" :title="row.title" :size="32" />
              <div class="text">
                <strong>{{ row.title || row.conch }} <KindBadge :kind="row.driver" /></strong>
                <span class="mono hm-muted">{{ row.conch }}</span>
                <span v-if="row.description" class="desc hm-muted">{{ row.description }}</span>
                <span v-if="row.authUse" class="desc" data-test="needs-authenticator">
                  需要授权器
                  <el-button link type="primary" class="mono" @click="showAuthenticator">{{ row.authUse }}</el-button>
                  <el-tag v-if="missingAuthenticator(row)" type="warning" effect="plain" size="small">本机还没有</el-tag>
                </span>
                <span v-if="isAuthenticator(row) && consumersOf(row).length" class="desc hm-muted" data-test="used-by">
                  被这些业务包使用：{{ consumersOf(row).join('、') }}
                </span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最新版本" width="110">
          <template #default="{ row }"><span class="mono">{{ row.version ? `v${row.version}` : '—' }}</span></template>
        </el-table-column>
        <el-table-column v-if="tab === 'packages'" label="能力" width="80">
          <template #default="{ row }"><span class="num">{{ row.capabilityCount }}</span></template>
        </el-table-column>
        <el-table-column label="本机状态" min-width="200">
          <template #default="{ row }">
            <ShellSwapLoader v-if="installing === row.conch" compact label="正在换上新壳…" data-test="row-swap" />
            <div v-else class="tags">
              <el-tag v-if="row.localConflict" type="warning" effect="plain" size="small" data-test="local-active-tag">本机同名的本地包生效中</el-tag>
              <el-tag v-if="row.subscribed" type="success" effect="plain" size="small">已订阅</el-tag>
              <el-tag v-else-if="!row.localConflict" type="info" effect="plain" size="small">未订阅</el-tag>
              <el-tag v-if="row.ruleDisabled" type="danger" effect="plain" size="small" data-test="rule-disabled-tag">已停用</el-tag>
              <el-tag v-else-if="row.installed && !row.localConflict && !row.repairRequired" type="info" effect="plain" size="small" class="mono">
                已安装 v{{ row.installedVersion }}
              </el-tag>
              <span v-if="row.message" class="damage" data-test="repair-message">{{ row.message }}</span>
              <span v-if="row.fixHint" class="damage" data-test="fix-hint">{{ row.fixHint }}</span>
              <el-tag
                v-if="needsRepair(row) && !row.message"
                type="warning"
                effect="plain"
                size="small"
                :title="syncReasonLabels[repairReason(row) ?? ''] ?? repairReason(row) ?? ''"
                data-test="repair-tag"
              >
                需要修复
              </el-tag>
              <el-tag v-if="!needsRepair(row) && row.updateAvailable" type="warning" effect="plain" size="small" class="mono">有新版本 v{{ row.latestVersion }}</el-tag>
              <el-tag v-if="row.disabled" type="warning" effect="plain" size="small">Reef 已停用，本机仍可调用</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button v-if="row.installed && !needsRepair(row)" link type="primary" @click="open(row)">管理</el-button>
            <el-button v-if="needsRepair(row)" link type="warning" :loading="busy === row.conch" data-test="repair" @click="repair(row)">修复</el-button>
            <el-button v-else-if="row.updateAvailable" link type="primary" :loading="busy === row.conch" @click="update(row)">更新</el-button>
            <el-button v-if="row.subscribed" link type="danger" :loading="busy === row.conch" @click="unsubscribe(row)">取消订阅</el-button>
            <el-button v-else link type="primary" :loading="busy === row.conch" @click="subscribe(row)">订阅</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.load-failed {
  margin-bottom: 12px;
}

.tabs {
  margin-bottom: 10px;
}

.tabs :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-hint {
  margin: 0 0 8px;
  font-size: 12.5px;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 12.5px;
}

.swap {
  margin-bottom: 8px;
}

.meta b {
  color: var(--hm-text);
}

.name {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.text .mono {
  font-size: 12px;
}

.desc {
  font-size: 12px;
  line-height: 1.5;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.damage {
  flex-basis: 100%;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.5;
}
</style>
