<script setup lang="ts">
import { isAdmin } from '../auth'
import { Bell, Box, CircleCheckFilled, DataLine, Refresh, Upload, Warning } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import BarChart from '@shared/components/BarChart.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import { notifyError, percent, relativeTime } from '@shared/format'
import { api, type Alert, type RollbackEvent, type ConchSummary, type TideSummary } from '../api'
import { refreshOpenAlerts } from '../alerts'
import { alertKindLabels, crabLabel, isoDaysAgo, versionChangeReasons } from '../labels'
import { toBars, totals } from '../tide'

const loading = ref(true)
const conches = ref<ConchSummary[]>([])
const tide = ref<TideSummary | null>(null)
const alerts = ref<Alert[]>([])
const rollbacks = ref<RollbackEvent[]>([])

const published = computed(() => conches.value.filter((s) => s.version).length)
const week = computed(() => totals(tide.value?.series ?? []))
const bars = computed(() => toBars(tide.value?.series ?? [], 'day', tide.value?.from, tide.value?.to))

async function load() {
  loading.value = true
  try {
    const from = isoDaysAgo(7)
    const [s, t, a, r] = await Promise.all([
      api.conches(),
      api.tideSummary({ from, bucket: 'day' }),
      api.alerts('open'),
      api.tideRollbacks({ from }),
    ])
    conches.value = s
    tide.value = t
    alerts.value = a.alerts
    rollbacks.value = r.events
    void refreshOpenAlerts()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <PageHeader title="概览" description="最近 7 天的规则包发布与调用情况">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button v-if="isAdmin" type="primary" :icon="Upload" @click="$router.push('/conches/upload')">上传规则包</el-button>
    </PageHeader>

    <div class="hm-grid cols-4">
      <StatCard label="规则包总数" :value="conches.length" :hint="`已发布 ${published} 个`" :icon="Box" to="/conches" />
      <StatCard label="7 天调用" :value="week.calls" hint="来自各 Crab 的汇总上报" tone="ok" :icon="DataLine" to="/tide" />
      <StatCard
        label="7 天失败率"
        :value="percent(week.rate)"
        :hint="`失败 ${week.failures} 次`"
        :tone="week.rate > 0.1 ? 'danger' : week.rate > 0.02 ? 'warn' : 'ok'"
        :icon="Warning"
        to="/tide"
      />
      <StatCard
        label="未处理告警"
        :value="alerts.length"
        :tone="alerts.length ? 'danger' : 'default'"
        hint="需要人工确认"
        :icon="Bell"
        to="/alerts"
      />
    </div>

    <div class="hm-grid cols-2 row">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>每日调用量</span>
          <router-link to="/tide" class="more">查看 Tide</router-link>
        </div>
        <BarChart :points="bars" :height="220" :loading="loading" />
      </div>

      <div class="hm-card">
        <div class="hm-card-title">
          <span>未处理告警</span>
          <router-link to="/alerts" class="more">全部告警</router-link>
        </div>
        <ul v-if="alerts.length" class="list">
          <li v-for="alert in alerts.slice(0, 6)" :key="alert.id">
            <el-tag type="danger" size="small" effect="plain">{{ alertKindLabels[alert.kind] ?? alert.kind }}</el-tag>
            <router-link :to="`/conches/${encodeURIComponent(alert.conch)}`" class="mono">{{ alert.conch }}</router-link>
            <span v-if="alert.conchVersion" class="hm-muted mono">v{{ alert.conchVersion }}</span>
            <span class="spacer" />
            <span class="hm-muted">{{ relativeTime(alert.createdAt) }}</span>
          </li>
        </ul>
        <div v-else-if="!loading" class="clear">
          <el-icon :size="40" class="ok-icon"><CircleCheckFilled /></el-icon>
          <strong>一切正常</strong>
          <span class="hm-muted">没有需要处理的告警，新告警会在这里出现</span>
        </div>
      </div>
    </div>

    <div class="hm-card row">
      <div class="hm-card-title"><span>最近的版本切换</span></div>
      <el-table :data="rollbacks.slice(0, 10)">
        <template #empty>{{ loading ? '' : '7 天内没有 Crab 切换版本' }}</template>
        <el-table-column label="规则包" min-width="160">
          <template #default="{ row }">
            <router-link :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono">{{ row.conch }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="版本变化" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.fromVersion }} → {{ row.toVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ versionChangeReasons[row.reason] ?? row.reason }}</template>
        </el-table-column>
        <el-table-column label="Crab" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ crabLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="120">
          <template #default="{ row }">{{ relativeTime(row.occurredAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.row {
  margin-top: 16px;
}

.more {
  font-size: 13px;
  font-weight: 400;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid var(--hm-border);
}

.list li:last-child {
  border-bottom: none;
}

.spacer {
  flex: 1;
}

.clear {
  height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
}

.ok-icon {
  color: var(--hm-success);
  margin-bottom: 4px;
  filter: drop-shadow(0 6px 12px rgba(22, 163, 74, 0.25));
}
</style>
