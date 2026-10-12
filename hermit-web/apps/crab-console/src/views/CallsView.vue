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

const loading = ref(false)
const calls = ref<CallSummary[]>([])
const shells = ref<ShellSummary[]>([])
const filter = reactive({ shell: '', action: '', limit: 100, failedOnly: false })
const drawer = reactive({ open: false, loading: false, call: null as CallDetail | null })

const visible = computed(() => (filter.failedOnly ? calls.value.filter((c) => !c.success) : calls.value))

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

      <el-table v-loading="loading" :data="visible" empty-text="没有调用记录" row-class-name="clickable" @row-click="onRow">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.at) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small" effect="plain">{{ row.success ? '成功' : '失败' }}</el-tag>
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
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">{{ millis(row.durationMs) }}</template>
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
    </div>

    <el-drawer v-model="drawer.open" size="560px" title="调用详情">
      <div v-loading="drawer.loading">
        <template v-if="drawer.call">
          <el-alert
            v-if="!drawer.call.success"
            type="error"
            :closable="false"
            show-icon
            :title="`${errorCodeLabels[drawer.call.errorCode ?? ''] ?? drawer.call.errorCode}（${drawer.call.errorCode}）`"
            :description="drawer.call.errorMessage ?? ''"
            class="notice"
          />
          <dl class="hm-kv">
            <dt>traceId</dt>
            <dd>
              <code>{{ drawer.call.traceId }}</code>
              <el-button link :icon="CopyDocument" @click="copyText(drawer.call.traceId, 'traceId 已复制')" />
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
</style>
