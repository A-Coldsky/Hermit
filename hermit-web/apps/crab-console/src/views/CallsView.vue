<script setup lang="ts">
import { CopyDocument, Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText, errorCodeLabels, formatTime, millis, notifyError } from '@shared/format'
import { api, type CallDetail, type CallSummary, type ShellSummary } from '../api'
import { originLabels, stepTypeLabels } from '../labels'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const calls = ref<CallSummary[]>([])
const shells = ref<ShellSummary[]>([])
const filter = reactive({ shell: '', action: '', limit: 100, failedOnly: route.query.failed === '1' })
const page = reactive({ current: 1, size: 20 })
const drawer = reactive({ open: false, loading: false, call: null as CallDetail | null })

const visible = computed(() => (filter.failedOnly ? calls.value.filter((c) => !c.success) : calls.value))
const paged = computed(() => visible.value.slice((page.current - 1) * page.size, page.current * page.size))

const summary = computed(() => {
  const list = calls.value
  const failed = list.filter((c) => !c.success).length
  const durations = list.map((c) => c.durationMs).sort((a, b) => a - b)
  const avg = durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : null
  const p95 = durations.length ? durations[Math.min(durations.length - 1, Math.ceil(durations.length * 0.95) - 1)] : null
  return { total: list.length, ok: list.length - failed, failed, avg, p95 }
})

const slowest = computed(() => Math.max(1, ...visible.value.map((c) => c.durationMs)))

async function load() {
  loading.value = true
  try {
    calls.value = (
      await api.calls({ shell: filter.shell || undefined, action: filter.action.trim() || undefined, limit: filter.limit })
    ).calls
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function openTrace(traceId: string) {
  drawer.open = true
  drawer.loading = true
  drawer.call = null
  try {
    drawer.call = await api.call(traceId)
  } catch (error) {
    notifyError(error)
  } finally {
    drawer.loading = false
  }
}

function failureTitle(code: string | null): string {
  const label = errorCodeLabels[code ?? ''] ?? code ?? '调用失败'
  return code && code !== 'AUTH_FAILED' ? `${label}（${code}）` : label
}

function onRow(row: CallSummary) {
  void router.replace({ query: { ...route.query, trace: row.traceId } })
}

watch(
  () => route.query.trace,
  (trace) => {
    if (typeof trace === 'string' && trace) {
      void openTrace(trace)
    }
  },
  { immediate: true },
)

watch(
  () => drawer.open,
  (open) => {
    if (!open && route.query.trace) {
      const { trace: _trace, ...rest } = route.query
      void router.replace({ query: rest })
    }
  },
)

watch(() => [filter.shell, filter.limit], load)
watch(() => [filter.failedOnly, calls.value], () => (page.current = 1))

onMounted(async () => {
  void load()
  try {
    shells.value = (await api.shells()).shells
  } catch {
    // 下拉选项缺失不影响列表
  }
})
</script>

<template>
  <div>
    <PageHeader title="调用记录" description="本机最近的能力调用。日志里的凭证和会话 Cookie 已脱敏">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-toolbar">
        <el-select v-model="filter.shell" placeholder="全部 Shell" clearable class="pick">
          <el-option v-for="s in shells" :key="s.shell" :label="s.shell" :value="s.shell" />
        </el-select>
        <el-input v-model="filter.action" placeholder="能力名" clearable class="pick" @keyup.enter="load" @clear="load" />
        <el-select v-model="filter.limit" class="limit">
          <el-option v-for="n in [50, 100, 200, 500]" :key="n" :label="`最近 ${n} 条`" :value="n" />
        </el-select>
        <el-checkbox v-model="filter.failedOnly">只看失败</el-checkbox>
      </div>

      <div v-if="summary.total" class="summary">
        <div><span class="hm-muted">共</span><b class="num">{{ summary.total }}</b><span class="hm-muted">次</span></div>
        <div class="ok"><i />成功 <b class="num">{{ summary.ok }}</b></div>
        <div class="bad"><i />失败 <b class="num">{{ summary.failed }}</b></div>
        <div><span class="hm-muted">平均耗时</span><b class="num">{{ millis(summary.avg) }}</b></div>
        <div><span class="hm-muted">P95</span><b class="num">{{ millis(summary.p95) }}</b></div>
      </div>

      <el-table v-loading="loading" :data="paged" row-class-name="clickable" @row-click="onRow">
        <template #empty>{{ loading ? '' : '没有调用记录' }}</template>
        <el-table-column label="时间" width="186">
          <template #default="{ row }">{{ formatTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <span class="result" :class="row.success ? 'ok' : 'bad'"><i />{{ row.success ? '成功' : '失败' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="能力" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.shell }}.{{ row.action }}</span>
            <span class="mono hm-muted"> v{{ row.shellVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="账号" width="120">
          <template #default="{ row }"><span class="mono">{{ row.account ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="耗时" width="150">
          <template #default="{ row }">
            <div class="dur">
              <span class="bar"><span :class="{ slow: row.durationMs >= 1000 }" :style="{ width: `${(row.durationMs / slowest) * 100}%` }" /></span>
              <span class="num">{{ millis(row.durationMs) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="!row.success">
              <span class="err">{{ errorCodeLabels[row.errorCode ?? ''] ?? row.errorCode }}</span>
              <span class="hm-muted"> {{ row.errorMessage ?? '' }}</span>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="visible.length > page.size" class="pager">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="visible.length"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          size="small"
        />
      </div>
    </div>

    <el-drawer v-model="drawer.open" size="560px" title="调用详情">
      <div v-loading="drawer.loading">
        <template v-if="drawer.call">
          <el-alert
            v-if="!drawer.call.success"
            type="error"
            :closable="false"
            show-icon
            :title="failureTitle(drawer.call.errorCode)"
            :description="drawer.call.errorMessage ?? ''"
            class="notice"
          />
          <dl class="hm-kv">
            <dt>traceId</dt>
            <dd>
              <code>{{ drawer.call.traceId }}</code>
              <el-button link :icon="CopyDocument" aria-label="复制 traceId" @click="copyText(drawer.call.traceId, 'traceId 已复制')" />
            </dd>
            <dt>时间</dt>
            <dd>{{ formatTime(drawer.call.at) }}</dd>
            <dt>能力</dt>
            <dd class="mono">{{ drawer.call.shell }}.{{ drawer.call.action }} v{{ drawer.call.shellVersion }}</dd>
            <dt>来源</dt>
            <dd>{{ originLabels[drawer.call.origin] ?? drawer.call.origin }}</dd>
            <dt>账号</dt>
            <dd class="mono">{{ drawer.call.account ?? '—' }}</dd>
            <dt>API Key</dt>
            <dd class="mono">{{ drawer.call.apiKeyId ?? '—' }}</dd>
            <dt>总耗时</dt>
            <dd>{{ millis(drawer.call.durationMs) }}</dd>
          </dl>

          <h4>执行步骤</h4>
          <el-empty v-if="!drawer.call.steps.length" :image-size="48" description="没有步骤记录" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="step in drawer.call.steps"
              :key="step.index"
              :timestamp="millis(step.durationMs)"
              placement="top"
              :type="step.status !== null && step.status >= 400 ? 'danger' : 'primary'"
            >
              <div class="step">
                <strong>#{{ step.index }} {{ stepTypeLabels[step.type] ?? step.type }}</strong>
                <span v-if="step.method" class="mono">{{ step.method }} {{ step.path }}</span>
                <el-tag v-if="step.status !== null" size="small" :type="step.status >= 400 ? 'danger' : 'success'" effect="plain">
                  HTTP {{ step.status }}
                </el-tag>
                <span v-if="step.extractCount !== null" class="hm-muted">提取 {{ step.extractCount }} 个字段</span>
              </div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.pick {
  width: 200px;
}

.limit {
  width: 140px;
}

.err {
  color: var(--hm-danger);
}

.notice {
  margin-bottom: 16px;
}

h4 {
  margin: 24px 0 12px;
}

.step {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

:deep(.clickable) {
  cursor: pointer;
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  padding: 10px 14px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: var(--hm-subtle);
  border: 1px solid var(--hm-border);
  font-size: 13px;
}

.summary > div {
  display: flex;
  align-items: center;
  gap: 6px;
}

.summary b {
  font-weight: 650;
}

.summary i,
.result i {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.summary .ok i,
.result.ok i {
  background: var(--hm-success);
}

.summary .bad i,
.result.bad i {
  background: var(--hm-danger);
}

.result {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.result.bad {
  color: var(--hm-danger);
  font-weight: 500;
}

.dur {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dur .bar {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: #edf1f3;
  overflow: hidden;
}

.dur .bar span {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: #5eb8ad;
}

.dur .bar span.slow {
  background: var(--hm-accent);
}

.dur .num {
  width: 56px;
  text-align: right;
  font-size: 12.5px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
