<script setup lang="ts">
defineProps<{ label: string; value: string | number; hint?: string; tone?: 'default' | 'warn' | 'danger' | 'ok' }>()
</script>

<template>
  <div class="stat hm-card" :class="tone ?? 'default'">
    <div class="label"><i class="dot" />{{ label }}</div>
    <div class="value hm-num">{{ value }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat {
  --tone: var(--el-color-primary);
  position: relative;
  overflow: hidden;
  transition: box-shadow 0.2s, transform 0.2s var(--hm-ease);
}

.stat::after {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 3px;
  background: var(--tone);
  opacity: 0.85;
}

.stat:hover {
  box-shadow: var(--hm-shadow-raised);
}

.stat.warn {
  --tone: var(--hm-accent);
}

.stat.danger {
  --tone: var(--hm-danger);
}

.stat.ok {
  --tone: var(--hm-success);
}

.label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--hm-muted);
  font-size: 13px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--tone);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--tone) 18%, transparent);
}

.value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
  margin-top: 10px;
  letter-spacing: -0.5px;
}

.hint {
  color: var(--hm-muted);
  font-size: 12px;
  margin-top: 4px;
}
</style>
