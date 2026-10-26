<script setup lang="ts">
import { DataLine, Operation, Warning, WarningFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import BarChart from '@shared/components/BarChart.vue'
import StatCard from '@shared/components/StatCard.vue'
import { millis, notifyError, percent } from '@shared/format'
import { api, type FailureGroup, type ConchVersion, type TideSummary, type VersionStats } from '../api'
import { rangePresets, rangeQuery } from '../range'
import { toBars, totals, totalsByAction } from '../tide'
import FailureGroups from './FailureGroups.vue'

const props = defineProps<{ conch: string; versions: ConchVersion[] }>()

const range = ref('7d')
const loading = ref(true)
const summary = ref<TideSummary | null>(null)
const groups = ref<FailureGroup[]>([])
const compare = ref<VersionStats[]>([])
const failureVersion = ref('')

const published = computed(() => props.versions.filter((v) => v.status === 'PUBLISHED').slice(0, 5))
const bucket = computed(() => rangeQuery(range.value).bucket)
const bars = computed(() => toBars(summary.value?.series ?? [], bucket.value, summary.value?.from, summary.value?.to))
const sum = computed(() => totals(summary.value?.series ?? []))
const actions = computed(() => totalsByAction(summary.value?.series ?? []))

async function load() {
  loading.value = true
  try {
    const query = rangeQuery(range.value)
    const versionList = published.value.map((v) => v.version).join(',')
    const [s, f, c] = await Promise.all([
      api.tideSummary({ conch: props.conch, ...query }),
      api.tideFailures({ conch: props.conch, from: query.from, version: failureVersion.value || undefined }),
      versionList ? api.tideCompare(props.conch, versionList) : Promise.resolve({ conch: props.conch, versions: [] }),
    ])
    summary.value = s
    groups.value = f.groups
    compare.value = c.versions
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

watch([range, failureVersion], load)
watch(() => props.versions, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="stats">
    <div class="hm-toolbar">
      <el-radio-group v-model="range">
        <el-radio-button v-for="p in rangePresets" :key="p.key" :value="p.key">{{ p.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div class="hm-grid cols-4">
      <StatCard label="调用次数" :value="sum.calls" :icon="DataLine" />
      <StatCard label="失败次数" :value="sum.failures" :tone="sum.failures ? 'warn' : 'ok'" :icon="WarningFilled" />
      <StatCard label="失败率" :value="percent(sum.rate)" :tone="sum.rate > 0.1 ? 'danger' : sum.rate > 0.02 ? 'warn' : 'ok'" :icon="Warning" />
      <StatCard label="涉及能力" :value="actions.length" :icon="Operation" />
    </div>

    <div class="hm-grid cols-2">
      <div class="hm-card">
        <div class="hm-card-title"><span>调用趋势</span></div>
        <BarChart :points="bars" :height="220" :loading="loading" />
      </div>
      <div class="hm-card">
        <div class="hm-card-title">
          <span>版本对比</span>
          <span class="hm-muted small">全部历史累计，最近 5 个已发布版本</span>
        </div>
        <el-table :data="compare">
          <template #empty>{{ loading ? '' : '还没有已发布版本' }}</template>
          <el-table-column label="版本" min-width="100">
            <template #default="{ row }"><span class="mono">v{{ row.version }}</span></template>
          </el-table-column>
          <el-table-column prop="calls" label="调用" align="right" />
          <el-table-column prop="failures" label="失败" align="right" />
          <el-table-column label="失败率" align="right">
            <template #default="{ row }">
              <span :class="{ danger: row.failureRate > 0.1 }">{{ percent(row.failureRate) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>按能力</span>
        <span class="hm-muted small">P95 取各小时 P95 的最大值，是近似值</span>
      </div>
      <el-table :data="actions">
        <template #empty>{{ loading ? '' : '所选范围内没有调用' }}</template>
        <el-table-column label="能力" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column prop="success" label="成功" align="right" />
        <el-table-column prop="failure" label="失败" align="right" />
        <el-table-column label="失败率" align="right">
          <template #default="{ row }">{{ percent(row.failureRate) }}</template>
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
        <el-select v-model="failureVersion" placeholder="全部版本" clearable class="pick">
          <el-option v-for="v in versions" :key="v.version" :label="`v${v.version}`" :value="v.version" />
        </el-select>
      </div>
      <FailureGroups :groups="groups" :loading="loading" />
    </div>
  </div>
</template>

<style scoped>
.stats {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.stats .hm-card + .hm-card {
  margin-top: 0;
}

.small {
  font-size: 12px;
  font-weight: 400;
}

.danger {
  color: var(--hm-danger);
  font-weight: 600;
}

.pick {
  width: 160px;
}
</style>
