<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ name: string; title?: string; size?: number }>()

const palette = ['#0f766e', '#1d4ed8', '#7c3aed', '#b45309', '#be185d', '#0369a1', '#4d7c0f']

const color = computed(() => {
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
      '--tint': color,
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
  color: var(--tint);
  background: color-mix(in srgb, var(--tint) 13%, var(--hm-surface));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--tint) 12%, transparent);
}

:global(html.dark) .avatar {
  color: color-mix(in srgb, var(--tint) 45%, #ffffff);
  background: color-mix(in srgb, var(--tint) 28%, var(--hm-surface));
}
</style>
