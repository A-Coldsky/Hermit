<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ name: string; title?: string; size?: number }>()

const palette = [
  ['#0f766e', '#ccfbf1'],
  ['#1d4ed8', '#dbeafe'],
  ['#7c3aed', '#ede9fe'],
  ['#b45309', '#fef3c7'],
  ['#be185d', '#fce7f3'],
  ['#0369a1', '#e0f2fe'],
  ['#4d7c0f', '#ecfccb'],
]

const colors = computed(() => {
  let hash = 0
  for (const ch of props.name) {
    hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  }
  return palette[hash % palette.length]
})

const letter = computed(() => (props.title?.trim() || props.name).slice(0, 1).toUpperCase())
</script>

<template>
  <span
    class="avatar"
    aria-hidden="true"
    :style="{
      color: colors[0],
      background: colors[1],
      width: `${size ?? 40}px`,
      height: `${size ?? 40}px`,
      fontSize: `${Math.round((size ?? 40) * 0.42)}px`,
    }"
  >{{ letter }}</span>
</template>

<style scoped>
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 11px;
  font-weight: 700;
  box-shadow: inset 0 0 0 1px rgba(16, 39, 43, 0.06);
}
</style>
