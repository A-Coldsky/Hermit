<script setup lang="ts">
import { Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, ref, watch } from 'vue'
import JsonBlock from '@shared/components/JsonBlock.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { formatTime, notifyError, relativeTime } from '@shared/format'
import { api, type Alert } from '../api'
import { refreshOpenAlerts } from '../alerts'
import { alertKindLabels } from '../labels'

const status = ref<'open' | 'all'>('open')
const loading = ref(true)
/** 只有最近一次加载成功时才说「没有告警」，失败时列表为空不代表没有告警。 */
const loaded = ref(false)
const alerts = ref<Alert[]>([])
const acking = ref<number | null>(null)

async function load(quiet = false) {
  if (!quiet) {
    loading.value = true
  }
  const wanted = status.value
  try {
    const result = (await api.alerts(wanted)).alerts
    if (wanted === status.value) {
      alerts.value = result
      loaded.value = true
    }
  } catch (error) {
    if (!quiet) {
      loaded.value = false
    }
    notifyError(error)
  } finally {
    if (wanted === status.value) {
      loading.value = false
    }
  }
}

async function ack(alert: Alert) {
  acking.value = alert.id
  try {
    const updated = await api.ackAlert(alert.id)
    ElMessage.success('已确认')
    alerts.value =
      status.value === 'open'
        ? alerts.value.filter((a) => a.id !== alert.id)
        : alerts.value.map((a) => (a.id === alert.id ? { ...a, ...updated } : a))
    await Promise.all([load(true), refreshOpenAlerts()])
  } catch (error) {
    notifyError(error)
  } finally {
    acking.value = null
  }
}

watch(status, () => load())
onMounted(() => load())
</script>

<template>
  <div>
    <PageHeader title="告警" description="Tide 汇总时发现的异常：新版本失败率上升、规则不匹配或老系统错误激增">
      <el-radio-group v-model="status">
        <el-radio-button value="open">未处理</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" @click="load()">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <el-table v-loading="loading" :data="alerts">
        <template #empty>{{ loading || !loaded ? '' : status === 'open' ? '没有未处理的告警' : '还没有告警' }}</template>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <JsonBlock v-if="row.detail" :value="row.detail" />
              <span v-else class="hm-muted">没有附加信息</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="160">
          <template #default="{ row }">
            <el-tag :type="row.acknowledgedAt ? 'info' : 'danger'" effect="plain">{{ alertKindLabels[row.kind] ?? row.kind }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Shell" min-width="160">
          <template #default="{ row }">
            <router-link :to="`/shells/${encodeURIComponent(row.shell)}?tab=stats`" class="mono">{{ row.shell }}</router-link>
            <span v-if="row.shellVersion" class="mono hm-muted"> v{{ row.shellVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="产生时间" width="180">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.createdAt)">
              <span>{{ relativeTime(row.createdAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="处理" min-width="200">
          <template #default="{ row }">
            <span v-if="row.acknowledgedAt" class="hm-muted">{{ row.acknowledgedBy }} 于 {{ formatTime(row.acknowledgedAt) }} 确认</span>
            <el-button v-else type="primary" plain size="small" :icon="Check" :loading="acking === row.id" @click="ack(row)">
              确认
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.detail {
  padding: 0 24px;
}
</style>
