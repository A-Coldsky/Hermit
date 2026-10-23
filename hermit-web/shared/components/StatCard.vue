<script setup lang="ts">
import type { Component } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'

defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'default' | 'warn' | 'danger' | 'ok'
  icon?: Component
  to?: RouteLocationRaw
}>()
</script>

<template>
  <component :is="to ? RouterLink : 'div'" :to="to" class="stat hm-card" :class="[tone ?? 'default', { clickable: to }]">
    <div class="head">
      <span class="label">{{ label }}</span>
      <span class="chip">
        <el-icon v-if="icon" :size="16"><component :is="icon" /></el-icon>
        <span v-else class="dot" />
      </span>
    </div>
    <div class="value hm-num">{{ value }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </component>
</template>

<style scoped>
.stat {
  display: block;
  color: inherit;
  --tone: var(--el-color-primary);
  --tone-soft: var(--hm-teal-soft);
  position: relative;
  overflow: hidden;
  padding: 18px 20px;
}

.stat.ok {
  --tone: var(--hm-success);
  --tone-soft: var(--el-color-success-light-9);
}

.stat.warn {
  --tone: var(--el-color-warning-dark-2);
  --tone-soft: var(--el-color-warning-light-9);
}

.stat.danger {
  --tone: var(--hm-danger);
  --tone-soft: var(--el-color-danger-light-9);
}

.stat::after {
  content: '';
  position: absolute;
  right: -28px;
  top: -28px;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--tone-soft) 0%, transparent 70%);
  opacity: 0.9;
  pointer-events: none;
}

.head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.label {
  color: var(--hm-muted);
  font-size: 13px;
}

.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  color: var(--tone);
  background: var(--hm-surface);
  box-shadow: 0 0 0 1px var(--tone-soft), var(--hm-shadow);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--tone);
}

.value {
  position: relative;
  z-index: 1;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.15;
  margin-top: 8px;
  letter-spacing: -0.6px;
}

.stat.danger .value {
  color: var(--hm-danger);
}

.hint {
  position: relative;
  z-index: 1;
  color: var(--hm-muted);
  font-size: 12px;
  margin-top: 4px;
}
</style>
