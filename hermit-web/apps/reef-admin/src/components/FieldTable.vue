<script setup lang="ts">
import { computed } from 'vue'
import type { FieldDeclaration } from '../api'
import { credentialTypeLabels } from '../labels'

const props = defineProps<{ fields: Record<string, FieldDeclaration>; empty: string; withDefault?: boolean }>()

const rows = computed(() => Object.entries(props.fields).map(([name, field]) => ({ name, ...field })))

function show(value: unknown): string {
  if (value === undefined || value === null) {
    return '—'
  }
  return typeof value === 'string' ? value : JSON.stringify(value)
}
</script>

<template>
  <el-table :data="rows" :empty-text="empty">
    <el-table-column label="字段" min-width="160">
      <template #default="{ row }">
        <span class="mono">{{ row.name }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="label" label="显示名" min-width="140" />
    <el-table-column label="类型" width="100">
      <template #default="{ row }">{{ credentialTypeLabels[row.type] ?? row.type }}</template>
    </el-table-column>
    <el-table-column label="必填" width="80">
      <template #default="{ row }">
        <el-tag v-if="row.required" size="small" type="warning" effect="plain">必填</el-tag>
        <span v-else class="hm-muted">选填</span>
      </template>
    </el-table-column>
    <el-table-column v-if="withDefault" label="默认值" min-width="120">
      <template #default="{ row }">
        <span class="mono">{{ show(row.default) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="说明" min-width="220">
      <template #default="{ row }">
        <span>{{ row.description ?? '' }}</span>
        <span v-if="row.options?.length" class="hm-muted">（可选：{{ row.options.join(' / ') }}）</span>
      </template>
    </el-table-column>
  </el-table>
</template>
