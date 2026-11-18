<script setup lang="ts">
import { formatTime, formatUtc } from '@shared/format'
import type { LocalConchHistoryEntry } from '../api'

defineProps<{
  entries: LocalConchHistoryEntry[]
  error: string | null
  loadingId: string | null
}>()

const emit = defineEmits<{ restore: [entry: LocalConchHistoryEntry] }>()

/** 接口没给时间时，从文件名前缀 `yyyyMMddHHmmssSSS` 还原成本机时间。 */
function when(entry: LocalConchHistoryEntry): string {
  if (entry.savedAt) return entry.savedAt
  const match = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{3})/.exec(entry.id)
  if (!match) return ''
  const [, year, month, day, hour, minute, second, millis] = match
  return `${year}-${month}-${day}T${hour}:${minute}:${second}.${millis}`
}
</script>

<template>
  <div class="history" data-test="local-history" @click.stop @keyup.enter.stop>
    <div class="history-title">历史版本</div>
    <p v-if="error" class="history-error" data-test="history-error">{{ error }}</p>
    <p v-else-if="!entries.length" class="hm-muted history-empty">还没有历史版本</p>
    <ul v-else>
      <li v-for="entry in entries" :key="entry.id" data-test="history-entry">
        <span class="mono" data-test="history-version">v{{ entry.version }}</span>
        <span class="hm-muted" :title="formatUtc(when(entry))">{{ formatTime(when(entry)) }}</span>
        <span class="spacer" />
        <el-button
          link
          type="primary"
          data-test="restore"
          :loading="loadingId === entry.id"
          @click.stop="emit('restore', entry)"
        >回退</el-button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.history-title {
  font-size: 12.5px;
  font-weight: 600;
}

.history ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}

.history :deep(.el-button) {
  font-size: 12.5px;
}

.spacer {
  flex: 1;
}

.history-empty,
.history-error {
  margin: 0;
  font-size: 12.5px;
}

.history-error {
  color: var(--hm-danger);
}
</style>
