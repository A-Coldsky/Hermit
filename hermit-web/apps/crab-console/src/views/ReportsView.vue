<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { Clock, Delete, Promotion, Refresh, RefreshLeft, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import TraceId from '@shared/components/TraceId.vue'
import { ApiError } from '@shared/http'
import { errorCodeLabels, millis, notifyError } from '@shared/format'
import { api, type ReportBatch, type ReportSettings } from '../api'

const loading = ref(true)
const sending = ref(false)
const configured = ref(true)
const batch = ref<ReportBatch | null>(null)
const dropped = ref(0)
const settings = ref<ReportSettings | null>(null)
const draft = ref<ReportSettings>({ enabled: true, intervalSeconds: 300 })
const savingSettings = ref(false)
const settingsChanged = computed(
  () =>
    !!settings.value &&
    (settings.value.enabled !== draft.value.enabled || settings.value.intervalSeconds !== draft.value.intervalSeconds),
)

async function loadSettings() {
  try {
    settings.value = await api.reportSettings()
    draft.value = { ...settings.value }
  } catch (error) {
    notifyError(error)
  }
}

async function saveSettings() {
  savingSettings.value = true
  try {
    settings.value = await api.updateReportSettings({ ...draft.value })
    draft.value = { ...settings.value }
    ElMessage.success(settings.value.enabled ? `上报已开启，每 ${minutes(settings.value.intervalSeconds)} 发送一次` : '上报已关闭，不再记录和发送')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    savingSettings.value = false
  }
}

function minutes(seconds: number): string {
  return seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`
}

const callTotal = computed(() => batch.value?.counts.reduce((n, c) => n + c.success + c.failure, 0) ?? 0)
const empty = computed(
  () => !batch.value || (!batch.value.counts.length && !batch.value.failures.length && !batch.value.events.length),
)

async function load() {
  loading.value = true
  try {
    const preview = await api.reportPreview()
    configured.value = true
    batch.value = preview.batch
    dropped.value = preview.dropped
  } catch (error) {
    if (error instanceof ApiError && error.code === 'NOT_CONFIGURED') {
      configured.value = false
    } else {
      notifyError(error)
    }
  } finally {
    loading.value = false
  }
}

async function send() {
  sending.value = true
  try {
    const result = await api.sendReport()
    if (!result.attempted) {
      ElMessage.info('没有需要上报的数据')
    } else if (result.accepted) {
      ElMessage.success('已上报到 Reef')
    } else if (result.status === 0) {
      ElMessage.error('无法连接到 Reef，上报未送达')
    } else {
      ElMessage.error(`Reef 拒绝了上报（HTTP ${result.status}），稍后会自动重试`)
    }
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    sending.value = false
  }
}

function refresh() {
  void load()
  void loadSettings()
}

onMounted(refresh)
</script>

<template>
  <div v-loading="loading" class="reports">
    <PageHeader title="上报" description="上报开关与下次发给 Reef 的内容。只包含计数、耗时和失败摘要，不包含请求参数、返回数据或凭证">
      <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!configured || empty" @click="send">立即上报</el-button>
    </PageHeader>

    <div class="hm-card settings" data-test="report-settings">
      <div class="hm-card-title"><span>上报设置</span></div>
      <div v-if="settings" class="settings-row">
        <el-switch v-model="draft.enabled" active-text="上报到 Reef" data-test="report-enabled" />
        <div class="interval">
          <span class="hm-muted">每</span>
          <el-input-number
            v-model="draft.intervalSeconds"
            :min="60"
            :step="60"
            :disabled="!draft.enabled"
            controls-position="right"
            data-test="report-interval"
          />
          <span class="hm-muted">秒发送一次</span>
        </div>
        <el-button type="primary" :disabled="!settingsChanged" :loading="savingSettings" data-test="report-save" @click="saveSettings">保存</el-button>
      </div>
      <p class="hm-muted hint">
        关闭后不再记录也不发送，本机调用记录不受影响，立即生效。调用计数先在内存里累计、约 30 秒写一次本地库，Crab 异常退出可能丢最近 30 秒的计数。
      </p>
    </div>

    <div v-if="!configured" class="hm-card off">
      <template v-if="settings && !settings.enabled">
        <strong>上报已关闭</strong>
        <span class="hm-muted">需要时在上面打开。</span>
      </template>
      <template v-else>
        <strong>还没有连接 Reef</strong>
        <span class="hm-muted">在「<router-link to="/reef">连接 Reef</router-link>」页导入初始化 JSON 后开始上报。</span>
      </template>
    </div>

    <template v-else-if="batch">
      <div class="hm-grid cols-4">
        <StatCard label="小时计数" :value="batch.counts.length" :hint="`覆盖 ${callTotal} 次调用`" :icon="Clock" />
        <StatCard label="失败样本" :value="batch.failures.length" :tone="batch.failures.length ? 'warn' : 'default'" :icon="WarningFilled" />
        <StatCard label="版本切换事件" :value="batch.events.length" :icon="RefreshLeft" />
        <StatCard label="已丢弃" :value="dropped" :tone="dropped ? 'warn' : 'default'" hint="队列满时丢弃的旧记录" :icon="Delete" />
      </div>

      <div class="hm-card">
        <dl class="hm-kv">
          <dt>实例 ID</dt>
          <dd class="mono">{{ batch.instanceId }}</dd>
          <dt>Crab 版本</dt>
          <dd class="mono">{{ batch.crabVersion }}</dd>
        </dl>
      </div>

      <div class="hm-card">
        <div class="hm-card-title"><span>按小时计数</span></div>
        <el-table :data="batch.counts" empty-text="没有待上报的计数" max-height="360">
          <el-table-column label="小时" width="220">
            <template #default="{ row }"><TimeText :value="row.hourStart" /></template>
          </el-table-column>
          <el-table-column label="能力" min-width="200">
            <template #default="{ row }">
              <span class="mono">{{ row.conch }}.{{ row.action }}</span>
              <span class="mono hm-muted"> v{{ row.conchVersion }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="success" label="成功" align="right" />
          <el-table-column prop="failure" label="失败" align="right" />
          <el-table-column label="平均" align="right">
            <template #default="{ row }">{{ millis(row.avgMillis) }}</template>
          </el-table-column>
          <el-table-column label="P95" align="right">
            <template #default="{ row }">{{ millis(row.p95Millis) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="hm-card">
        <div class="hm-card-title"><span>失败样本</span></div>
        <el-table :data="batch.failures" empty-text="没有待上报的失败" max-height="360">
          <el-table-column label="时间" width="220">
            <template #default="{ row }"><TimeText :value="row.occurredAt" /></template>
          </el-table-column>
          <el-table-column label="能力" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.conch }}.{{ row.action }}</span></template>
          </el-table-column>
          <el-table-column label="错误" width="140">
            <template #default="{ row }">
              <el-tag type="danger" size="small" effect="light" round>{{ errorCodeLabels[row.errorCode] ?? row.errorCode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
          <el-table-column label="traceId" width="150">
            <template #default="{ row }">
              <TraceId :value="row.traceId" :to="{ path: '/calls', query: { trace: row.traceId } }" />
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="batch.events.length" class="hm-card">
        <div class="hm-card-title"><span>版本切换事件</span></div>
        <el-table :data="batch.events">
          <el-table-column label="时间" width="220">
            <template #default="{ row }"><TimeText :value="row.occurredAt" /></template>
          </el-table-column>
          <el-table-column label="规则包" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.conch }}</span></template>
          </el-table-column>
          <el-table-column label="版本变化" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.fromVersion }} → {{ row.toVersion }}</span></template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="200" />
        </el-table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.reports {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.reports .hm-card + .hm-card {
  margin-top: 0;
}

.off {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 20px;
  text-align: center;
}
.settings {
  margin-bottom: 16px;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.interval {
  display: flex;
  align-items: center;
  gap: 8px;
}

.settings .hint {
  margin: 10px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
