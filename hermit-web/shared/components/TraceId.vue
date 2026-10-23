<script setup lang="ts">
import { CopyDocument } from '@element-plus/icons-vue'
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { copyText } from '@shared/format'

const props = defineProps<{ value: string; to?: RouteLocationRaw; length?: number }>()

const short = computed(() => props.value.slice(0, props.length ?? 8))
</script>

<template>
  <span class="trace" @click.stop>
    <el-tooltip :content="value" placement="top" :show-after="300">
      <RouterLink v-if="to" :to="to" class="mono id">{{ short }}</RouterLink>
      <span v-else class="mono id">{{ short }}</span>
    </el-tooltip>
    <button type="button" class="copy" title="复制 traceId" aria-label="复制 traceId" @click="copyText(value, 'traceId 已复制')">
      <el-icon :size="13"><CopyDocument /></el-icon>
    </button>
  </span>
</template>

<style scoped>
.trace {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.id {
  padding: 1px 6px;
  border-radius: 5px;
  background: var(--hm-subtle);
  border: 1px solid var(--hm-border);
}

.copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--hm-muted);
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 0.15s, background 0.15s;
}

.copy:hover {
  opacity: 1;
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
</style>
