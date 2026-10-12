<script setup lang="ts">
import { Refresh, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import BarChart from '@shared/components/BarChart.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import { notifyError, percent, relativeTime } from '@shared/format'
import { api, type Alert, type RollbackEvent, type ShellSummary, type TideSummary } from '../api'
import { refreshOpenAlerts } from '../alerts'
import { alertKindLabels, isoDaysAgo } from '../labels'
import { toBars, totals } from '../tide'

const loading = ref(false)
const shells = ref<ShellSummary[]>([])
const tide = ref<TideSummary | null>(null)
const alerts = ref<Alert[]>([])
const rollbacks = ref<RollbackEvent[]>([])

const published = computed(() => shells.value.filter((s) => s.version).length)
const week = computed(() => totals(tide.value?.series ?? []))
const bars = computed(() => toBars(tide.value?.series ?? [], 'day', tide.value?.from, tide.value?.to))

async function load() {
  loading.value = true
  try {
    const from = isoDaysAgo(7)
    const [s, t, a, r] = await Promise.all([
      api.shells(),
      api.tideSummary({ from, bucket: 'day' }),
      api.alerts('open'),
      api.tideRollbacks({ from }),
    ])
    shells.value = s
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
    <PageHeader title="概览" description="最近 7 天的 Shell 发布与调用情况">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Upload" @click="$router.push('/shells/upload')">上传 Shell</el-button>
    </PageHeader>

    <div class="hm-grid cols-4">
      <StatCard label="Shell 总数" :value="shells.length" :hint="`已发布 ${published} 个`" />
      <StatCard label="7 天调用" :value="week.calls" hint="来自各 Crab 的汇总上报" tone="ok" />
      <StatCard
        label="7 天失败率"
        :value="percent(week.rate)"
        :hint="`失败 ${week.failures} 次`"
        :tone="week.rate > 0.1 ? 'danger' : week.rate > 0.02 ? 'warn' : 'ok'"
      />
      <StatCard label="未处理告警" :value="alerts.length" :tone="alerts.length ? 'danger' : 'default'" hint="需要人工确认" />
    </div>

    <div class="hm-grid cols-2 row">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>每日调用量</span>
          <router-link to="/tide" class="more">查看 Tide</router-link>
        </div>
        <BarChart :points="bars" :height="220" />
      </div>

      <div class="hm-card">
        <div class="hm-card-title">
          <span>未处理告警</span>
          <router-link to="/alerts" class="more">全部告警</router-link>
        </div>
        <el-empty v-if="!alerts.length" description="一切正常" :image-size="64" />
        <ul v-else class="list">
          <li v-for="alert in alerts.slice(0, 6)" :key="alert.id">
            <el-tag type="danger" size="small" effect="plain">{{ alertKindLabels[alert.kind] ?? alert.kind }}</el-tag>
            <router-link :to="`/shells/${encodeURIComponent(alert.shell)}`" class="mono">{{ alert.shell }}</router-link>
            <span v-if="alert.shellVersion" class="hm-muted mono">v{{ alert.shellVersion }}</span>
            <span class="spacer" />
            <span class="hm-muted">{{ relativeTime(alert.createdAt) }}</span>
          </li>
        </ul>
      </div>
    </div>

    <div class="hm-card row">
      <div class="hm-card-title"><span>最近的自动回退</span></div>
      <el-table :data="rollbacks.slice(0, 10)" empty-text="7 天内没有 Crab 发生自动回退">
        <el-table-column label="Shell" min-width="160">
          <template #default="{ row }">
            <router-link :to="`/shells/${encodeURIComponent(row.shell)}`" class="mono">{{ row.shell }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="版本变化" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.fromVersion }} → {{ row.toVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        <el-table-column prop="instanceId" label="Crab 实例" min-width="140" show-overflow-tooltip />
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
</style>
