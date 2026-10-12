<script setup lang="ts">
import { computed, ref } from 'vue'

export interface BarPoint {
  label: string
  success: number
  failure: number
}

const props = defineProps<{ points: BarPoint[]; height?: number }>()

const hovered = ref<number | null>(null)
const chartHeight = computed(() => props.height ?? 200)
const max = computed(() => Math.max(1, ...props.points.map((p) => p.success + p.failure)))
const barWidth = computed(() => (props.points.length ? 100 / props.points.length : 100))

function heightOf(value: number): number {
  return (value / max.value) * (chartHeight.value - 24)
}
</script>

<template>
  <div class="chart" :style="{ height: `${chartHeight}px` }">
    <div v-if="!points.length" class="empty hm-muted">所选范围内没有调用数据</div>
    <svg v-else :viewBox="`0 0 100 ${chartHeight}`" preserveAspectRatio="none" class="svg">
      <g v-for="(point, index) in points" :key="point.label" @mouseenter="hovered = index" @mouseleave="hovered = null">
        <rect :x="index * barWidth" y="0" :width="barWidth" :height="chartHeight" fill="transparent" />
        <rect
          :x="index * barWidth + barWidth * 0.18"
          :y="chartHeight - heightOf(point.success + point.failure)"
          :width="barWidth * 0.64"
          :height="heightOf(point.success)"
          :class="['ok', { dim: hovered !== null && hovered !== index }]"
          rx="0.6"
        />
        <rect
          :x="index * barWidth + barWidth * 0.18"
          :y="chartHeight - heightOf(point.failure)"
          :width="barWidth * 0.64"
          :height="heightOf(point.failure)"
          :class="['bad', { dim: hovered !== null && hovered !== index }]"
          rx="0.6"
        />
      </g>
    </svg>
    <div v-if="hovered !== null && points[hovered]" class="tip">
      <strong>{{ points[hovered].label }}</strong>
      <span>成功 {{ points[hovered].success }}</span>
      <span class="fail">失败 {{ points[hovered].failure }}</span>
    </div>
    <div v-if="points.length" class="axis hm-muted">
      <span>{{ points[0].label }}</span>
      <span>{{ points[points.length - 1].label }}</span>
    </div>
  </div>
</template>

<style scoped>
.chart {
  position: relative;
  padding-bottom: 20px;
}

.svg {
  width: 100%;
  height: 100%;
  display: block;
}

.ok {
  fill: #5eb8ad;
  transition: opacity 0.12s;
}

.bad {
  fill: #ef4444;
  transition: opacity 0.12s;
}

.dim {
  opacity: 0.35;
}

.axis {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.tip {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
  box-shadow: var(--hm-shadow);
}

.tip .fail {
  color: var(--hm-danger);
}

.empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
