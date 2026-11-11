<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { notifyError } from '@shared/format'
import { api, type CapabilityState } from '../api'

const props = defineProps<{ conch: string; capabilities: CapabilityState[]; emptyText?: string }>()
const busy = ref<string | null>(null)

async function toggle(cap: CapabilityState, enabled: string | number | boolean) {
  busy.value = cap.action
  try {
    const result = await api.setCapability(props.conch, cap.action, Boolean(enabled))
    cap.disabled = result.disabled
    ElMessage.success(result.disabled ? `已停用 ${cap.action}` : `已启用 ${cap.action}`)
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div class="hm-card">
    <div class="hm-card-title"><span>能力</span></div>
    <p class="hm-muted hint">停用后这个能力不再出现在 OpenAPI 文档和 MCP 工具列表里，调用会被拒绝。</p>
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
          <el-switch :model-value="!row.disabled" :loading="busy === row.action" @change="(v: string | number | boolean) => toggle(row, v)" />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.strong {
  font-weight: 600;
}
</style>
