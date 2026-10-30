<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { DataLine, Refresh, RefreshLeft, Warning, WarningFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import BarChart from '@shared/components/BarChart.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import { millis, notifyError, percent } from '@shared/format'
import { api, type FailureGroup, type RollbackEvent, type ConchSummary, type TideSummary } from '../api'
import FailureGroups from '../components/FailureGroups.vue'
import { rangePresets, rangeQuery } from '../range'
import { toBars, totals, totalsByAction } from '../tide'

const range = ref('7d')
const conch = ref('')
const loading = ref(false)
const conches = ref<ConchSummary[]>([])
const summary = ref<TideSummary | null>(null)
const groups = ref<FailureGroup[]>([])
const rollbacks = ref<RollbackEvent[]>([])

const bucket = computed(() => rangeQuery(range.value).bucket)
const bars = computed(() => toBars(summary.value?.series ?? [], bucket.value, summary.value?.from, summary.value?.to))
const sum = computed(() => totals(summary.value?.series ?? []))
const actions = computed(() => totalsByAction(summary.value?.series ?? []))
const failureTotal = computed(() => groups.value.reduce((n, g) => n + g.count, 0))

async function load() {
  loading.value = true
  try {
    const query = rangeQuery(range.value)
    const filter = conch.value || undefined
    const [s, f, r] = await Promise.all([
      api.tideSummary({ conch: filter, ...query }),
      api.tideFailures({ conch: filter, from: query.from }),
      api.tideRollbacks({ conch: filter, from: query.from }),
    ])
    summary.value = s
    groups.value = f.groups
    rollbacks.value = r.events
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    conches.value = await api.conches()
  } catch (error) {
    notifyError(error)
  }
})

watch([range, conch], load, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="tide">
    <PageHeader title="Tide 统计" description="各 Crab 按小时汇总上报的调用量、耗时和失败样本">
      <el-select v-model="conch" placeholder="全部规则包" clearable filterable class="pick">
        <el-option v-for="s in conches" :key="s.conch" :label="s.title ? `${s.title}（${s.conch}）` : s.conch" :value="s.conch" />
      </el-select>
      <el-radio-group v-model="range">
        <el-radio-button v-for="p in rangePresets" :key="p.key" :value="p.key">{{ p.label }}</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-grid cols-4">
      <StatCard label="调用次数" :value="sum.calls" :icon="DataLine" />
      <StatCard label="失败次数" :value="sum.failures" :tone="sum.failures ? 'warn' : 'ok'" :icon="WarningFilled" />
      <StatCard label="失败率" :value="percent(sum.rate)" :tone="sum.rate > 0.1 ? 'danger' : sum.rate > 0.02 ? 'warn' : 'ok'" :icon="Warning" />
      <StatCard label="自动回退" :value="rollbacks.length" :tone="rollbacks.length ? 'danger' : 'default'" hint="Crab 因新版本异常切回旧版" :icon="RefreshLeft" />
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>调用趋势</span>
        <span class="hm-muted small">按{{ bucket === 'hour' ? '小时' : '天' }}汇总</span>
      </div>
      <BarChart :points="bars" :height="240" :loading="loading" />
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>按能力</span>
        <span class="hm-muted small">P95 取各小时 P95 的最大值，是近似值</span>
      </div>
      <el-table :data="actions" max-height="420">
        <template #empty>{{ loading ? '' : '所选范围内没有调用' }}</template>
        <el-table-column label="Conch" min-width="140">
          <template #default="{ row }">
            <router-link :to="`/conches/${encodeURIComponent(row.conch)}?tab=stats`" class="mono">{{ row.conch }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="能力" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column prop="success" label="成功" align="right" sortable />
        <el-table-column prop="failure" label="失败" align="right" sortable />
        <el-table-column label="失败率" align="right" sortable :sort-method="(a: any, b: any) => a.failureRate - b.failureRate">
          <template #default="{ row }">
            <span :class="{ danger: row.failureRate > 0.1 }">{{ percent(row.failureRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平均耗时" align="right">
          <template #default="{ row }">{{ millis(row.avgMillis) }}</template>
        </el-table-column>
        <el-table-column label="P95（各时段最高）" align="right" min-width="130">
          <template #default="{ row }">{{ millis(row.p95Millis) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>失败下钻</span>
        <span class="hm-muted small">共 {{ failureTotal }} 条失败样本，按版本、能力、步骤和错误码分组</span>
      </div>
      <FailureGroups :groups="groups" :loading="loading" show-conch />
    </div>

    <div class="hm-card">
      <div class="hm-card-title"><span>自动回退记录</span></div>
      <el-table :data="rollbacks">
        <template #empty>{{ loading ? '' : '所选范围内没有自动回退' }}</template>
        <el-table-column label="Conch" min-width="140">
          <template #default="{ row }"><span class="mono">{{ row.conch }}</span></template>
        </el-table-column>
        <el-table-column label="版本变化" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.fromVersion }} → {{ row.toVersion }}</span></template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        <el-table-column prop="instanceId" label="Crab 实例" min-width="160" show-overflow-tooltip />
        <el-table-column label="发生时间" width="230">
          <template #default="{ row }"><TimeText :value="row.occurredAt" /></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.tide {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tide .hm-card + .hm-card {
  margin-top: 0;
}

.pick {
  width: 220px;
}

.small {
  font-size: 12px;
  font-weight: 400;
}

.danger {
  color: var(--hm-danger);
  font-weight: 600;
}
</style>
