<script setup lang="ts">
import { Box, CircleCheckFilled, Connection, List, Refresh, Setting, Warning, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import TraceId from '@shared/components/TraceId.vue'
import { ApiError } from '@shared/http'
import { errorCodeLabels, formatTime, notifyError, percent, relativeTime } from '@shared/format'
import { api, type CallSummary, type CrabRelease, type DebugState, type ConchSummary, type SyncReport } from '../api'
import { outcomeLabels, syncReasonLabels } from '../labels'
import { refreshPending } from '../status'
import { repairConch } from '../update'

const router = useRouter()

const loading = ref(true)
const syncing = ref(false)
const conches = ref<ConchSummary[]>([])
const calls = ref<CallSummary[]>([])
const debug = ref<DebugState>({ enabled: false, expiresAt: null })
const syncConfigured = ref(true)
const report = ref<SyncReport | null>(null)
const latestCrab = ref<CrabRelease | null>(null)
const syncedAt = ref<string | null>(null)

const loaded = ref(false)
const pending = computed(() => conches.value.filter((s) => s.needsConfiguration))
const paused = computed(() => pending.value.filter((s) => s.loginPaused))
const unconfigured = computed(() => pending.value.filter((s) => !s.loginPaused))
const disabledInSync = computed(() => report.value?.conches.filter((s) => s.kind === 'DISABLED').map((s) => s.conch) ?? [])
const removedOnly = computed(() => report.value?.removed.filter((name) => !disabledInSync.value.includes(name)) ?? [])
const show = (value: number | string) => (loaded.value ? value : '—')
const failures = computed(() => calls.value.filter((c) => !c.success).length)
const repairs = computed(() => report.value?.conches.filter((s) => s.kind === 'REPAIR_REQUIRED') ?? [])
const repairing = ref<string | null>(null)
const debugBusy = ref(false)
const changedConches = computed(
  () =>
    report.value?.conches.filter((s) => s.kind !== 'SKIPPED_SAME' && s.kind !== 'NOT_SUBSCRIBED' && s.kind !== 'REPAIR_REQUIRED') ?? [],
)
const updatable = computed(() => conches.value.filter((s) => s.updateAvailable))
const unsubscribedCount = computed(() => report.value?.conches.filter((s) => s.kind === 'NOT_SUBSCRIBED').length ?? 0)

async function loadSync() {
  try {
    const status = await api.syncStatus()
    syncConfigured.value = true
    report.value = status.report
    latestCrab.value = status.latestCrab
  } catch (error) {
    if (error instanceof ApiError && error.code === 'NOT_CONFIGURED') {
      syncConfigured.value = false
    } else {
      throw error
    }
  }
}

async function load() {
  loading.value = true
  try {
    const [s, c, d] = await Promise.all([api.conches(), api.calls({ limit: 100 }), api.debug(), loadSync()])
    conches.value = s.conches
    calls.value = c.calls
    debug.value = d
    loaded.value = true
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function syncNow() {
  syncing.value = true
  try {
    report.value = await api.sync()
    latestCrab.value = report.value.latestCrab
    syncedAt.value = new Date().toISOString()
    const installed = report.value.conches.filter((s) => s.kind === 'INSTALLED').length
    conches.value = (await api.conches()).conches
    const waiting = [
      repairs.value.length ? `${repairs.value.length} 个规则包需要修复` : '',
      updatable.value.length ? `${updatable.value.length} 个规则包有新版本待手动更新` : '',
    ]
      .filter(Boolean)
      .map((text) => `，${text}`)
      .join('')
    const message = report.value.notModified ? `Reef 目录没有变化${waiting}` : `同步完成，安装了 ${installed} 个规则包${waiting}`
    if (repairs.value.length) {
      ElMessage.warning(message)
    } else {
      ElMessage.success(message)
    }
    void refreshPending()
  } catch (error) {
    notifyError(error)
    try {
      await loadSync()
    } catch (statusError) {
      notifyError(statusError)
    }
  } finally {
    syncing.value = false
  }
}

async function toggleDebug(enabled: string | number | boolean) {
  const on = Boolean(enabled)
  try {
    if (on) {
      await ElMessageBox.confirm(
        '打开后，本机本地规则包的调用和试跑会记录脱敏后的请求与响应。24 小时后自动关闭，并立刻删除这些追踪。正式包不受影响。',
        '打开开发者模式',
        { confirmButtonText: '打开', cancelButtonText: '取消', type: 'warning' },
      )
    } else {
      await ElMessageBox.confirm(
        '关闭后会立刻删除本机全部开发追踪，不能恢复。调用记录仍会保留。',
        '关闭开发者模式',
        { confirmButtonText: '关闭并删除追踪', cancelButtonText: '保持开启', type: 'warning', confirmButtonType: 'danger' },
      )
    }
  } catch {
    return
  }
  debugBusy.value = true
  try {
    debug.value = await api.setDebug(on)
    ElMessage.success(on ? '已打开开发者模式' : '已关闭开发者模式，追踪已删除')
  } catch (error) {
    notifyError(error)
  } finally {
    debugBusy.value = false
  }
}

async function repair(conch: string, reason: string | null) {
  repairing.value = conch
  try {
    if (await repairConch(conch, reason)) {
      void refreshPending()
      await load()
    }
  } finally {
    repairing.value = null
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="overview">
    <PageHeader title="概览" description="本机 Crab 的规则包、账号与最近调用">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :loading="syncing" :disabled="!syncConfigured" @click="syncNow">立即同步</el-button>
    </PageHeader>

    <el-alert
      v-if="latestCrab?.latestVersion"
      type="info"
      show-icon
      :closable="false"
      :title="`Reef 上有 Crab ${latestCrab?.latestVersion} 可用`"
      class="notice"
    >
      <template v-if="latestCrab.downloadUrl" #default>
        下载地址：<a :href="latestCrab.downloadUrl" target="_blank" rel="noopener">{{ latestCrab.downloadUrl }}</a>
      </template>
    </el-alert>

    <el-alert v-if="unconfigured.length" type="warning" show-icon :closable="false" class="notice">
      <template #title>
        {{ unconfigured.length }} 个规则包还缺凭证或配置：
        <router-link v-for="s in unconfigured" :key="s.conch" :to="`/conches/${encodeURIComponent(s.conch)}`" class="mono pending">
          {{ s.conch }}
        </router-link>
      </template>
    </el-alert>
    <el-alert v-if="paused.length" type="warning" show-icon :closable="false" class="notice">
      <template #title>
        {{ paused.length }} 个规则包有账号暂停了自动登录，进入详情查看是哪个账号和恢复方法：
        <router-link v-for="s in paused" :key="s.conch" :to="`/conches/${encodeURIComponent(s.conch)}`" class="mono pending">
          {{ s.conch }}
        </router-link>
      </template>
    </el-alert>

    <div class="hm-grid cols-4">
      <StatCard
        label="已安装规则包"
        :value="show(conches.length)"
        :hint="loaded ? `${conches.reduce((n, s) => n + s.capabilityCount, 0)} 个能力` : ''"
        :icon="Box"
        to="/conches"
      />
      <StatCard
        label="待配置"
        :value="show(pending.length)"
        :tone="!loaded ? 'default' : pending.length ? 'warn' : 'ok'"
        hint="缺凭证、配置，或有账号暂停自动登录"
        :icon="Setting"
        to="/conches"
      />
      <StatCard label="最近调用" :value="show(calls.length)" hint="最多显示最近 100 次" :icon="List" to="/calls" />
      <StatCard
        label="其中失败"
        :value="show(failures)"
        :hint="!loaded ? '' : calls.length ? `失败率 ${percent(failures / calls.length)}` : '暂无调用'"
        :tone="!loaded ? 'default' : failures ? 'danger' : 'ok'"
        :icon="WarningFilled"
        :to="{ path: '/calls', query: { failed: '1' } }"
      />
    </div>

    <div class="hm-grid cols-2">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>同步状态</span>
          <span v-if="syncedAt" class="hm-muted small">{{ relativeTime(syncedAt) }}同步</span>
        </div>
        <template v-if="report">
          <el-alert
            v-if="report.error"
            type="error"
            show-icon
            :closable="false"
            :title="report.error.message"
            class="notice"
          />
          <div v-if="repairs.length" class="state warn" data-test="repairs">
            <el-icon :size="22"><WarningFilled /></el-icon>
            <div class="grow">
              <strong>{{ repairs.length }} 个规则包需要修复，暂时不能调用</strong>
              <div class="hm-muted">本机文件或版本指针出了问题，同步不会自动重新下载。修复会先重新载入本机验过签的版本。</div>
              <ul class="repairs">
                <li v-for="item in repairs" :key="item.conch">
                  <span class="mono">{{ item.conch }}{{ item.version ? ` v${item.version}` : '' }}</span>
                  <span v-if="item.reason" class="hm-muted">{{ syncReasonLabels[item.reason] ?? item.reason }}</span>
                  <el-button size="small" type="warning" plain :loading="repairing === item.conch" @click="repair(item.conch, item.reason)">修复</el-button>
                </li>
              </ul>
            </div>
          </div>
          <div v-if="!report.error && updatable.length" class="state warn" data-test="updatable">
            <el-icon :size="22"><WarningFilled /></el-icon>
            <div>
              <strong>{{ updatable.length }} 个规则包有新版本，需要手动更新</strong>
              <div>
                <router-link v-for="s in updatable" :key="s.conch" :to="`/conches/${encodeURIComponent(s.conch)}`" class="mono pending">
                  {{ s.conch }} v{{ s.latestVersion }}
                </router-link>
              </div>
            </div>
          </div>
          <div v-if="report.notModified && !report.error && disabledInSync.length" class="state warn">
            <el-icon :size="22"><WarningFilled /></el-icon>
            <div>
              <strong>{{ disabledInSync.length }} 个规则包 Reef 已停用</strong>
              <div class="hm-muted">本机保留的版本仍可调用，但不会再更新。上次同步时 Reef 没有新变化</div>
            </div>
          </div>
          <div v-else-if="report.notModified && !report.error && !updatable.length && !repairs.length" class="state ok">
            <el-icon :size="22"><CircleCheckFilled /></el-icon>
            <div>
              <strong>已是最新</strong>
              <div class="hm-muted">上次同步时 Reef 没有变化</div>
            </div>
          </div>
          <ul class="outcomes">
            <li v-for="item in changedConches" :key="item.conch">
              <el-tag :type="outcomeLabels[item.kind ?? '']?.tone ?? 'info'" size="small" effect="plain">
                {{ outcomeLabels[item.kind ?? '']?.text ?? item.kind }}
              </el-tag>
              <span class="mono">{{ item.conch }} v{{ item.version }}</span>
              <span v-if="item.reason" class="hm-muted">{{ syncReasonLabels[item.reason] ?? item.reason }}</span>
              <router-link v-if="item.kind === 'UPDATE_AVAILABLE'" :to="`/conches/${encodeURIComponent(item.conch)}`">去更新</router-link>
            </li>
            <li v-for="name in removedOnly" :key="`rm-${name}`">
              <el-tag type="info" size="small" effect="plain">已移除</el-tag>
              <span class="mono">{{ name }}</span>
            </li>
          </ul>
          <p v-if="unsubscribedCount" class="hm-muted unsubscribed" data-test="unsubscribed">
            Reef 上还有 {{ unsubscribedCount }} 个规则包本机没有订阅，<router-link to="/catalog">去订阅</router-link>
          </p>
          <el-alert
            v-for="change in report.credentialChanges.filter((c) => c.addedRequired.length)"
            :key="change.conch"
            type="warning"
            :closable="false"
            show-icon
            :title="`${change.conch} 新增必填凭证：${change.addedRequired.join('、')}`"
            class="notice"
          />
        </template>
        <div v-else-if="!loading && !syncConfigured" class="state">
          <el-icon :size="22"><Connection /></el-icon>
          <div>
            <strong>没有配置 Reef，只使用本地规则包。</strong>
            <div class="hm-muted">在<router-link to="/reef">连接 Reef</router-link>导入 Reef 管理页生成的初始化 JSON，不用重启。</div>
          </div>
        </div>
        <div v-else-if="!loading" class="state">
          <el-icon :size="22"><Connection /></el-icon>
          <div>
            <strong>本次启动后还没有同步过</strong>
            <div class="hm-muted">点击右上角「立即同步」从 Reef 拉取最新规则包</div>
          </div>
        </div>
      </div>

      <div class="hm-card">
        <div class="hm-card-title">
          <span>调试开关</span>
          <el-tag v-if="debug.enabled" type="warning" effect="plain" size="small">开发者模式</el-tag>
        </div>
        <div class="debug">
          <el-switch
            :model-value="debug.enabled"
            :loading="debugBusy"
            active-text="开启"
            data-test="debug-switch"
            @change="toggleDebug"
          />
          <p class="hm-muted">
            打开后，只对本机本地规则包记录脱敏后的请求与响应，并允许试跑和刷新工具列表。正式包不受影响，调用记录照常。
          </p>
          <p v-if="debug.enabled && debug.expiresAt">
            <el-icon><Warning /></el-icon>
            将于 {{ formatTime(debug.expiresAt) }} 自动关闭，并删除这些追踪
          </p>
        </div>
      </div>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>最近失败</span>
        <router-link to="/calls" class="small">全部调用</router-link>
      </div>
      <el-table
        :data="calls.filter((c) => !c.success).slice(0, 8)"
        row-class-name="clickable"
        @row-click="(row: CallSummary) => router.push({ path: '/calls', query: { trace: row.traceId } })"
      >
        <template #empty>{{ loading ? '' : '最近没有失败的调用' }}</template>
        <el-table-column label="时间" width="120">
          <template #default="{ row }">{{ relativeTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="能力" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.conch }}.{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column label="错误" width="200">
          <template #default="{ row }">
            <el-tag type="danger" size="small" effect="light" round :title="row.errorCode ?? ''">
              {{ errorCodeLabels[row.errorCode ?? ''] ?? '调用失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="信息" min-width="220" show-overflow-tooltip />
        <el-table-column label="traceId" width="150">
          <template #default="{ row }">
            <TraceId :value="row.traceId" :to="{ path: '/calls', query: { trace: row.traceId } }" />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.overview .hm-card + .hm-card {
  margin-top: 0;
}

.notice {
  margin: 0;
}

.pending {
  margin-left: 6px;
}

.small {
  font-size: 12px;
  font-weight: 400;
}

.unsubscribed {
  margin: 8px 0 0;
  font-size: 12.5px;
}

.outcomes {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}

.outcomes li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}

.state {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--hm-subtle);
  border: 1px solid var(--hm-border);
  color: var(--hm-muted);
  line-height: 1.6;
}

.state strong {
  color: var(--hm-text);
  font-weight: 600;
}

.grow {
  flex: 1;
  min-width: 0;
}

.repairs {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.repairs li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 0;
}

.state + .state {
  margin-top: 8px;
}

.state.ok {
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-8);
  color: var(--hm-success);
}

.state.warn {
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-8);
  color: var(--el-color-warning-dark-2);
}

:deep(.clickable) {
  cursor: pointer;
}

.debug p {
  margin: 12px 0 0;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
