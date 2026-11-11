<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import TimeText from '@shared/components/TimeText.vue'
import { notifyError } from '@shared/format'
import { api, type Catalog, type CatalogConch } from '../api'
import { outcomeLabels, syncReasonLabels } from '../labels'
import { refreshPending } from '../status'
import { unsubscribeMessage } from '../copy'
import { repairConch, updateConch } from '../update'

const router = useRouter()
const loading = ref(true)
const syncing = ref(false)
const busy = ref<string | null>(null)
const catalog = ref<Catalog | null>(null)
/** 同步标为需要修复、但目录还没有 repairRequired 的包（例如指针丢失）。 */
const repairs = ref(new Map<string, string | null>())

function needsRepair(row: CatalogConch): boolean {
  return row.repairRequired || repairs.value.has(row.conch)
}

function repairReason(row: CatalogConch): string | null {
  return row.repairReason ?? repairs.value.get(row.conch) ?? null
}

const rows = computed(() => catalog.value?.conches ?? [])
const subscribedCount = computed(() => rows.value.filter((r) => r.subscribed).length)

async function load() {
  loading.value = true
  try {
    const [result, status] = await Promise.all([api.catalog(), api.syncStatus().catch(() => null)])
    catalog.value = result
    repairs.value = new Map(
      (status?.report?.conches ?? []).filter((s) => s.kind === 'REPAIR_REQUIRED').map((s) => [s.conch, s.reason] as const),
    )
  } catch (error) {
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
  busy.value = row.conch
  try {
    const result = await api.subscribe(row.conch)
    const kind = result.outcome?.kind
    const label = kind ? outcomeLabels[kind] : null
    if (!result.subscribed) {
      ElMessage.warning(`没有订阅 ${row.conch}：Reef 上当前不能订阅这个规则包`)
    } else if (kind === 'INSTALLED' || kind === 'SKIPPED_SAME') {
      ElMessage.success(`已订阅 ${row.conch}，已安装到本机`)
    } else if (!kind) {
      ElMessage.warning(`已订阅 ${row.conch}，但这次没有安装`)
    } else {
      const reason = result.outcome?.reason ? `：${result.outcome.reason}` : ''
      ElMessage.warning(`已订阅 ${row.conch}，但这次没有安装（${label?.text ?? kind}${reason}）`)
    }
    await load()
    void refreshPending()
  } catch (error) {
    notifyError(error)
    await load()
  } finally {
    busy.value = null
  }
}

async function unsubscribe(row: CatalogConch) {
  try {
    await ElMessageBox.confirm(
      unsubscribeMessage(row.conch),
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
    <PageHeader title="可订阅规则包" description="Reef 上已发布的规则包。订阅后本机才会下载安装；取消订阅会卸载规则包，但保留账号和凭证">
      <el-button :icon="Refresh" :loading="syncing" :disabled="catalog?.configured === false" @click="refreshFromReef">从 Reef 刷新</el-button>
    </PageHeader>

    <el-result
      v-if="catalog && !catalog.configured"
      icon="info"
      title="还没有连接 Reef"
      sub-title="在「连接 Reef」导入初始化 JSON 后才能看到可订阅的规则包。本地规则包不受影响"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/reef')">连接 Reef</el-button>
      </template>
    </el-result>

    <div v-else class="hm-card">
      <div v-if="catalog" class="meta hm-muted">
        <span>共 <b class="num">{{ rows.length }}</b> 个，已订阅 <b class="num">{{ subscribedCount }}</b> 个</span>
        <span v-if="catalog.syncedAt">目录更新于 <TimeText :value="catalog.syncedAt" /></span>
        <span v-else>还没有从 Reef 取到目录</span>
      </div>
      <p class="hm-scroll-hint">表格可以左右滑动，「订阅」在最右侧。</p>
      <el-table v-loading="loading" :data="rows">
        <template #empty>
          {{ loading ? '' : catalog?.syncedAt ? 'Reef 上还没有已发布的规则包' : '点「从 Reef 刷新」获取可订阅的规则包' }}
        </template>
        <el-table-column label="规则包" min-width="260">
          <template #default="{ row }">
            <div class="name">
              <ConchAvatar :name="row.conch" :title="row.title" :size="32" />
              <div class="text">
                <strong>{{ row.title || row.conch }}</strong>
                <span class="mono hm-muted">{{ row.conch }}</span>
                <span v-if="row.description" class="desc hm-muted">{{ row.description }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最新版本" width="110">
          <template #default="{ row }"><span class="mono">{{ row.version ? `v${row.version}` : '—' }}</span></template>
        </el-table-column>
        <el-table-column label="能力" width="80">
          <template #default="{ row }"><span class="num">{{ row.capabilityCount }}</span></template>
        </el-table-column>
        <el-table-column label="本机状态" min-width="200">
          <template #default="{ row }">
            <div class="tags">
              <el-tag v-if="row.localConflict" type="warning" effect="plain" size="small">与本地规则包同名</el-tag>
              <el-tag v-if="row.subscribed" type="success" effect="plain" size="small">已订阅</el-tag>
              <el-tag v-else-if="!row.localConflict" type="info" effect="plain" size="small">未订阅</el-tag>
              <el-tag v-if="row.ruleDisabled" type="danger" effect="plain" size="small" data-test="rule-disabled-tag">已停用</el-tag>
              <el-tag v-else-if="row.installed && !row.localConflict && !row.repairRequired" type="info" effect="plain" size="small" class="mono">
                已安装 v{{ row.installedVersion }}
              </el-tag>
              <span v-if="row.message" class="damage" data-test="repair-message">{{ row.message }}</span>
              <el-tag
                v-if="needsRepair(row) && !row.message"
                type="danger"
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
        <el-table-column label="操作" width="200" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.installed && !row.localConflict && !needsRepair(row)" link type="primary" @click="open(row)">管理</el-button>
            <el-button v-if="needsRepair(row)" link type="warning" :loading="busy === row.conch" data-test="repair" @click="repair(row)">修复</el-button>
            <el-button v-else-if="row.updateAvailable" link type="primary" :loading="busy === row.conch" @click="update(row)">更新</el-button>
            <el-button v-if="row.subscribed" link type="danger" :loading="busy === row.conch" @click="unsubscribe(row)">取消订阅</el-button>
            <el-tooltip v-else-if="row.localConflict" content="本机已有同名本地规则包。删除本地规则包后才能订阅" placement="top">
              <span><el-button link type="primary" disabled>订阅</el-button></span>
            </el-tooltip>
            <el-button v-else link type="primary" :loading="busy === row.conch" @click="subscribe(row)">订阅</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 12.5px;
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
