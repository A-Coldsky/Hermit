<script setup lang="ts">
import { formatTime, formatUtc } from '../format'

defineProps<{ value: string | null | undefined }>()
</script>

<template>
  <span class="hm-time" :title="formatUtc(value) || undefined">{{ formatTime(value) }}</span>
</template>
