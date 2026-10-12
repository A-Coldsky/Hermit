<script setup lang="ts">
defineProps<{ label: string; value: string | number; hint?: string; tone?: 'default' | 'warn' | 'danger' | 'ok' }>()
</script>

<template>
  <div class="stat hm-card" :class="tone ?? 'default'">
    <div class="label">{{ label }}</div>
    <div class="value">{{ value }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat {
  position: relative;
  overflow: hidden;
}

.stat::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: var(--el-color-primary);
}

.stat.warn::before {
  background: var(--hm-accent);
}

.stat.danger::before {
  background: var(--hm-danger);
}

.stat.ok::before {
  background: var(--hm-success);
}

.label {
  color: var(--hm-muted);
  font-size: 13px;
}

.value {
  font-size: 28px;
  font-weight: 700;
  margin-top: 6px;
  letter-spacing: -0.5px;
}

.hint {
  color: var(--hm-muted);
  font-size: 12px;
  margin-top: 4px;
}
</style>
