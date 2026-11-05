<script setup lang="ts">
import type { McpToolRow } from '../api'

defineProps<{ tools: McpToolRow[] }>()

function annotations(row: McpToolRow): string {
  const entries = Object.entries(row.upstreamAnnotations ?? {})
  return entries.length ? entries.map(([key, value]) => `${key}: ${JSON.stringify(value)}`).join('，') : '—'
}
</script>

<template>
  <el-table :data="tools" data-test="mcp-tools">
    <template #empty>包里没有工具</template>
    <el-table-column label="工具" min-width="180">
      <template #default="{ row }">
        <span class="mono">{{ row.name }}</span>
      </template>
    </el-table-column>
    <el-table-column label="上游名称" min-width="160">
      <template #default="{ row }">
        <span class="mono" :class="{ 'hm-muted': row.upstreamName === row.name }">{{ row.upstreamName ?? '—' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="类型" width="200">
      <template #default="{ row }">
        <el-tag v-if="row.readOnly" size="small" type="success" effect="plain">只读</el-tag>
        <el-tag v-else size="small" effect="plain">写入</el-tag>
        <el-tag v-if="row.destructive" size="small" type="danger" effect="plain" class="gap">破坏性</el-tag>
        <el-tag v-if="row.confirm" size="small" type="warning" effect="plain" class="gap">需确认</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="上游注解（包里记下的）" min-width="220">
      <template #default="{ row }">
        <span class="small">{{ annotations(row) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="参数摘要" width="140">
      <template #default="{ row }">
        <span class="mono small" :title="row.schemaSha256">{{ row.schemaSha256.slice(0, 12) }}</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.gap {
  margin-left: 4px;
}

.small {
  font-size: 12px;
}
</style>
