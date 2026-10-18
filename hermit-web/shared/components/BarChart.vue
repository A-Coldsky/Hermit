<script setup lang="ts">
import { computed, ref } from 'vue'

export interface BarPoint {
  label: string
  success: number
  failure: number
}

const props = defineProps<{ points: BarPoint[]; height?: number; loading?: boolean }>()

/** 底部留给坐标轴文字，顶部留给提示框不压住最高的柱子。 */
const AXIS = 20
const TOP = 8

const hovered = ref<number | null>(null)
const chartHeight = computed(() => props.height ?? 200)
const plotHeight = computed(() => chartHeight.value - AXIS)
const max = computed(() => niceMax(Math.max(1, ...props.points.map((p) => p.success + p.failure))))
const barWidth = computed(() => (props.points.length ? 100 / props.points.length : 100))
/** 刻度只标整数：最大值为 1 时中线没有意义，不画。 */
const grid = computed(() => [1, 0.5].filter((f) => Number.isInteger(max.value * f)).map((f) => ({ f, value: max.value * f })))

const ticks = computed(() => {
  const n = props.points.length
  if (!n) {
    return []
  }
  const picks = n === 1 ? [0] : n < 5 ? [0, n - 1] : [0, Math.floor((n - 1) / 2), n - 1]
  return picks.map((i) => props.points[i].label)
})

/** 提示框跟着柱子走，靠右半边时向左展开，避免溢出。 */
const tipStyle = computed(() => {
  if (hovered.value === null) {
    return {}
  }
  const center = (hovered.value + 0.5) * barWidth.value
  return center > 50 ? { right: `${100 - center}%` } : { left: `${center}%` }
})

function niceMax(value: number): number {
  const step = 10 ** Math.floor(Math.log10(value))
  for (const m of [1, 2, 2.5, 5, 10]) {
    if (value <= m * step) {
      return m * step
    }
  }
  return value
}

function heightOf(value: number): number {
  return (value / max.value) * (plotHeight.value - TOP)
}

function lineY(fraction: number): number {
  return plotHeight.value - fraction * (plotHeight.value - TOP)
}
</script>

<template>
  <div class="chart" :style="{ height: `${chartHeight}px` }">
    <div v-if="!points.length" class="empty hm-muted">{{ loading ? '' : '所选范围内没有调用数据' }}</div>
    <template v-else>
      <div class="plot" :style="{ height: `${plotHeight}px` }">
        <div class="area">
          <div v-for="line in grid" :key="line.f" class="grid" :style="{ top: `${lineY(line.f)}px` }">
            <span class="hm-num">{{ line.value }}</span>
          </div>
          <svg :viewBox="`0 0 100 ${plotHeight}`" preserveAspectRatio="none" class="svg" @mouseleave="hovered = null">
            <line x1="0" x2="100" :y1="plotHeight - 0.5" :y2="plotHeight - 0.5" class="base" />
            <g v-for="(point, index) in points" :key="point.label" @mouseenter="hovered = index">
              <rect :x="index * barWidth" y="0" :width="barWidth" :height="plotHeight" :class="['hit', { on: hovered === index }]" />
              <rect
                :x="index * barWidth + barWidth * 0.2"
                :y="plotHeight - heightOf(point.success + point.failure)"
                :width="barWidth * 0.6"
                :height="heightOf(point.success)"
                :class="['ok', { dim: hovered !== null && hovered !== index }]"
              />
              <rect
                :x="index * barWidth + barWidth * 0.2"
                :y="plotHeight - heightOf(point.failure)"
                :width="barWidth * 0.6"
                :height="heightOf(point.failure)"
                :class="['bad', { dim: hovered !== null && hovered !== index }]"
              />
            </g>
          </svg>
          <div v-if="hovered !== null && points[hovered]" class="tip" :style="tipStyle">
            <strong>{{ points[hovered].label }}</strong>
            <span><i class="key ok-key" />成功 {{ points[hovered].success }}</span>
            <span class="fail"><i class="key bad-key" />失败 {{ points[hovered].failure }}</span>
          </div>
        </div>
      </div>
      <div class="axis hm-muted">
        <span v-for="tick in ticks" :key="tick">{{ tick }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.chart {
  position: relative;
}

/* 左侧留出刻度数字的位置，柱子不被遮住。 */
.plot {
  padding-left: 36px;
}

.area {
  position: relative;
  height: 100%;
}

.svg {
  position: relative;
  width: 100%;
  height: 100%;
  display: block;
}

.grid {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--hm-chart-grid);
  pointer-events: none;
}

.grid span {
  position: absolute;
  top: -9px;
  right: calc(100% + 6px);
  font-size: 11px;
  line-height: 16px;
  color: var(--hm-muted);
}

.base {
  stroke: var(--hm-border);
  stroke-width: 1;
  vector-effect: non-scaling-stroke;
}

.hit {
  fill: transparent;
}

.hit.on {
  fill: var(--hm-surface-2);
}

.ok {
  fill: var(--hm-chart-ok);
  transition: opacity 0.12s;
}

.bad {
  fill: var(--hm-chart-bad);
  transition: opacity 0.12s;
}

.dim {
  opacity: 0.35;
}

.axis {
  padding-left: 36px;
  display: flex;
  justify-content: space-between;
  height: 20px;
  align-items: flex-end;
  font-size: 12px;
}

.tip {
  position: absolute;
  top: 0;
  display: flex;
  gap: 10px;
  white-space: nowrap;
  background: var(--el-bg-color-overlay, var(--hm-surface));
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
  box-shadow: var(--hm-shadow-raised);
  pointer-events: none;
  font-variant-numeric: tabular-nums;
}

.tip span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip .fail {
  color: var(--hm-danger);
}

.key {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.ok-key {
  background: var(--hm-chart-ok);
}

.bad-key {
  background: var(--hm-chart-bad);
}

.empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
