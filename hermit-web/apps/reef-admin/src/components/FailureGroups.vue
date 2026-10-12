<script setup lang="ts">
import { CopyDocument } from '@element-plus/icons-vue'
import { copyText, errorCodeLabels } from '@shared/format'
import type { FailureGroup } from '../api'

defineProps<{ groups: FailureGroup[]; showShell?: boolean }>()
</script>

<template>
  <el-table :data="groups" empty-text="所选范围内没有失败上报">
    <el-table-column type="expand">
      <template #default="{ row }">
        <ul class="recent">
          <li v-for="item in row.recent" :key="item.traceId">
            <code>{{ item.traceId }}</code>
            <el-button link :icon="CopyDocument" @click="copyText(item.traceId, 'traceId 已复制')" />
            <span>{{ item.summary ?? '（无摘要）' }}</span>
          </li>
        </ul>
        <p class="hm-muted hint">在对应 Crab 的控制台「调用记录」里用 traceId 查看完整步骤。</p>
      </template>
    </el-table-column>
    <el-table-column v-if="showShell" label="Shell" min-width="140">
      <template #default="{ row }">
        <router-link :to="`/shells/${encodeURIComponent(row.shell)}?tab=stats`" class="mono">{{ row.shell }}</router-link>
      </template>
    </el-table-column>
    <el-table-column label="版本" width="100">
      <template #default="{ row }"><span class="mono">v{{ row.shellVersion }}</span></template>
    </el-table-column>
    <el-table-column label="能力" min-width="140">
      <template #default="{ row }"><span class="mono">{{ row.action }}</span></template>
    </el-table-column>
    <el-table-column label="失败步骤" width="140">
      <template #default="{ row }">
        <span v-if="row.stepIndex !== null" class="mono">#{{ row.stepIndex }} {{ row.stepType ?? '' }}</span>
        <span v-else class="hm-muted">—</span>
      </template>
    </el-table-column>
    <el-table-column label="错误码" min-width="160">
      <template #default="{ row }">
        <el-tag type="danger" effect="plain" size="small">{{ errorCodeLabels[row.errorCode] ?? row.errorCode }}</el-tag>
        <span class="mono hm-muted code">{{ row.errorCode }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="count" label="次数" width="80" align="right" sortable />
  </el-table>
</template>

<style scoped>
.recent {
  list-style: none;
  margin: 0;
  padding: 0 24px;
}

.recent li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.hint {
  padding: 0 24px;
  margin: 6px 0 0;
  font-size: 12px;
}

.code {
  margin-left: 6px;
  font-size: 11px;
}
</style>
