<script setup lang="ts">
import { Refresh, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import { ApiError } from '@shared/http'
import { formatTime, notifyError, percent, relativeTime } from '@shared/format'
import { api, type CallSummary, type CrabRelease, type DebugState, type ShellSummary, type SyncReport } from '../api'
import { outcomeLabels } from '../labels'
import { refreshPending } from '../status'

const loading = ref(true)
const syncing = ref(false)
const shells = ref<ShellSummary[]>([])
const calls = ref<CallSummary[]>([])
const debug = ref<DebugState>({ enabled: false, expiresAt: null })
const syncConfigured = ref(true)
const report = ref<SyncReport | null>(null)
const latestCrab = ref<CrabRelease | null>(null)
const syncedAt = ref<string | null>(null)

const pending = computed(() => shells.value.filter((s) => s.needsConfiguration))
const failures = computed(() => calls.value.filter((c) => !c.success).length)
const changedShells = computed(() => report.value?.shells.filter((s) => s.kind !== 'SKIPPED_SAME') ?? [])

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
    const [s, c, d] = await Promise.all([api.shells(), api.calls({ limit: 200 }), api.debug(), loadSync()])
    shells.value = s.shells
    calls.value = c.calls
    debug.value = d
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
    const installed = report.value.shells.filter((s) => s.kind === 'INSTALLED').length
    ElMessage.success(report.value.notModified ? 'Reef 上没有新版本' : `同步完成，安装了 ${installed} 个 Shell`)
    shells.value = (await api.shells()).shells
    void refreshPending()
  } catch (error) {
    notifyError(error)
  } finally {
    syncing.value = false
  }
}

async function toggleDebug(enabled: string | number | boolean) {
  try {
    debug.value = await api.setDebug(Boolean(enabled))
    ElMessage.success(debug.value.enabled ? '已开启调试开关' : '已关闭调试开关')
  } catch (error) {
    notifyError(error)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="overview">
    <PageHeader title="概览" description="本机 Crab 的 Shell、账号与最近调用">
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

    <el-alert v-if="pending.length" type="warning" show-icon :closable="false" class="notice">
      <template #title>
        {{ pending.length }} 个 Shell 还缺凭证或配置，调用会返回「待配置」：
        <router-link v-for="s in pending" :key="s.shell" :to="`/shells/${encodeURIComponent(s.shell)}`" class="mono pending">
          {{ s.shell }}
        </router-link>
      </template>
    </el-alert>

    <div class="hm-grid cols-4">
      <StatCard label="已安装 Shell" :value="shells.length" :hint="`${shells.reduce((n, s) => n + s.capabilityCount, 0)} 个能力`" />
      <StatCard label="待配置" :value="pending.length" :tone="pending.length ? 'warn' : 'ok'" hint="缺凭证或配置项" />
      <StatCard label="最近调用" :value="calls.length" hint="最多显示最近 200 次" />
      <StatCard
        label="其中失败"
        :value="failures"
        :hint="calls.length ? `失败率 ${percent(failures / calls.length)}` : '暂无调用'"
        :tone="failures ? 'danger' : 'ok'"
      />
    </div>

    <div class="hm-grid cols-2">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>同步状态</span>
          <span v-if="syncedAt" class="hm-muted small">{{ relativeTime(syncedAt) }}同步</span>
        </div>
        <template v-if="report">
          <p v-if="report.notModified" class="hm-muted">上次同步时 Reef 没有变化。</p>
          <ul class="outcomes">
            <li v-for="item in changedShells" :key="item.shell">
              <el-tag :type="outcomeLabels[item.kind ?? '']?.tone ?? 'info'" size="small" effect="plain">
                {{ outcomeLabels[item.kind ?? '']?.text ?? item.kind }}
              </el-tag>
              <span class="mono">{{ item.shell }} v{{ item.version }}</span>
              <span v-if="item.reason" class="hm-muted">{{ item.reason }}</span>
            </li>
            <li v-for="name in report.removed" :key="`rm-${name}`">
              <el-tag type="info" size="small" effect="plain">已移除</el-tag>
              <span class="mono">{{ name }}</span>
            </li>
          </ul>
          <el-alert
            v-for="change in report.credentialChanges.filter((c) => c.addedRequired.length)"
            :key="change.shell"
            type="warning"
            :closable="false"
            show-icon
            :title="`${change.shell} 新增必填凭证：${change.addedRequired.join('、')}`"
            class="notice"
          />
        </template>
        <el-empty v-else-if="!loading && !syncConfigured" :image-size="60" description="没有配置 Reef，只使用本地 Shell。在 config.yml 设置 reef.url 和 reef.public_key 后重启 Crab。" />
        <el-empty v-else-if="!loading" :image-size="60" description="本次启动后还没有同步过" />
      </div>

      <div class="hm-card">
        <div class="hm-card-title">
          <span>调试开关</span>
          <el-tag type="info" effect="plain" size="small">未接入调用记录</el-tag>
        </div>
        <div class="debug">
          <el-switch :model-value="debug.enabled" active-text="开启" @change="toggleDebug" />
          <p class="hm-muted">当前版本只保存开关状态，不影响调用记录，也不会额外记录请求或返回内容。开启 24 小时后自动关闭。</p>
          <p v-if="debug.enabled && debug.expiresAt">
            <el-icon><Warning /></el-icon>
            将于 {{ formatTime(debug.expiresAt) }} 自动关闭
          </p>
        </div>
      </div>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>最近失败</span>
        <router-link to="/calls" class="small">全部调用</router-link>
      </div>
      <el-table :data="calls.filter((c) => !c.success).slice(0, 8)">
        <template #empty>{{ loading ? '' : '最近没有失败的调用' }}</template>
        <el-table-column label="时间" width="120">
          <template #default="{ row }">{{ relativeTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="能力" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.shell }}.{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="160" />
        <el-table-column prop="errorMessage" label="信息" min-width="240" show-overflow-tooltip />
        <el-table-column label="traceId" min-width="160">
          <template #default="{ row }">
            <router-link :to="{ path: '/calls', query: { trace: row.traceId } }" class="mono">{{ row.traceId }}</router-link>
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

.debug p {
  margin: 12px 0 0;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
