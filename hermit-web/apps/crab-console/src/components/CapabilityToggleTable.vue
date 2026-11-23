<script setup lang="ts">
import type { CapabilityState } from '../api'

defineProps<{
  conch: string
  capabilities: CapabilityState[]
  emptyText?: string
  ruleDisabled?: boolean
  busyAction: string | null
  busyGroup: string | null
}>()

const emit = defineEmits<{
  toggle: [cap: CapabilityState, enabled: boolean]
}>()
</script>

<template>
  <el-table :data="capabilities" :empty-text="emptyText || '没有能力'">
    <el-table-column label="能力" min-width="200">
      <template #default="{ row }">
        <div class="name">
          <span class="mono strong">{{ row.action }}</span>
          <span class="hm-muted">{{ row.title }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="方法" width="90">
      <template #default="{ row }">
        <el-tag :type="row.method === 'GET' ? 'success' : 'warning'" size="small" class="mono">{{ row.method }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="类型" width="90">
      <template #default="{ row }">{{ row.readOnly ? '只读' : '会修改' }}</template>
    </el-table-column>
    <el-table-column label="调用路径" min-width="220">
      <template #default="{ row }"><code>/api/{{ conch }}/{{ row.action }}</code></template>
    </el-table-column>
    <el-table-column label="启用" width="72" align="right" fixed="right">
      <template #default="{ row }">
        <el-switch
          :model-value="!row.disabled && !ruleDisabled"
          :disabled="ruleDisabled || busyGroup !== null"
          :loading="busyAction === row.action"
          :data-test="`cap-switch-${row.action}`"
          @change="(v: string | number | boolean) => emit('toggle', row, Boolean(v))"
        />
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.strong {
  font-weight: 600;
}
</style>
