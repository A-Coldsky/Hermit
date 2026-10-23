<script setup lang="ts">
import { computed, ref } from 'vue'

export interface BarPoint {
  label: string
  success: number
  failure: number
}

const props = defineProps<{ points: BarPoint[]; height?: number; loading?: boolean }>()

const hovered = ref<number | null>(null)
const chartHeight = computed(() => props.height ?? 200)
const plotHeight = computed(() => chartHeight.value - 44)

function niceMax(value: number): number {
  const step = 10 ** Math.floor(Math.log10(value))
  for (const m of [1, 2, 2.5, 5, 10]) {
    if (value <= m * step) {
      return m * step
    }
  }
  return value
}

const max = computed(() => niceMax(Math.max(1, ...props.points.map((p) => p.success + p.failure))))
/** 刻度只标整数，非整数的分位线不画，避免小量程时出现重复的数字。 */
const grid = computed(() =>
  [1, 0.75, 0.5, 0.25].filter((f) => Number.isInteger(max.value * f)).map((f) => ({ f, value: max.value * f })),
)

const ticks = computed(() => {
  const n = props.points.length
  if (!n) {
    return new Set<number>()
  }
  const every = Math.max(1, Math.ceil(n / 8))
  const picks = new Set<number>()
  for (let i = 0; i < n; i += every) {
    picks.add(i)
  }
  picks.add(n - 1)
  return picks
})

const totals = computed(() =>
  props.points.reduce((acc, p) => ({ success: acc.success + p.success, failure: acc.failure + p.failure }), {
    success: 0,
    failure: 0,
  }),
)

function pct(value: number): string {
  return `${(value / max.value) * 100}%`
}

const tipLeft = computed(() => {
  if (hovered.value === null || !props.points.length) {
    return '0%'
  }
  return `${((hovered.value + 0.5) / props.points.length) * 100}%`
})
</script>

<template>
  <div class="chart" :style="{ height: `${chartHeight}px` }">
    <div v-if="!points.length" class="empty hm-muted">{{ loading ? '' : '所选范围内没有调用数据' }}</div>
    <template v-else>
      <div class="legend">
        <span><i class="sw ok" />成功 <b class="num">{{ totals.success }}</b></span>
        <span><i class="sw bad" />失败 <b class="num">{{ totals.failure }}</b></span>
      </div>
      <div class="plot" :style="{ height: `${plotHeight}px` }">
        <div class="grid">
          <div v-for="line in grid" :key="line.f" class="line" :style="{ bottom: `${line.f * 100}%` }">
            <span class="y num">{{ line.value }}</span>
          </div>
          <div class="line base" />
        </div>
        <div class="bars" @mouseleave="hovered = null">
          <div
            v-for="(point, index) in points"
            :key="point.label"
            class="col"
            :class="{ dim: hovered !== null && hovered !== index, on: hovered === index }"
            @mouseenter="hovered = index"
          >
            <div v-if="point.success + point.failure === 0" class="stub" />
            <div v-else class="stack" :style="{ height: pct(point.success + point.failure) }">
              <div class="seg bad" :style="{ flexGrow: point.failure }" />
              <div class="seg ok" :style="{ flexGrow: point.success }" />
            </div>
          </div>
        </div>
        <div v-if="hovered !== null && points[hovered]" class="tip" :style="{ left: tipLeft }">
          <strong>{{ points[hovered].label }}</strong>
          <span><i class="sw ok" />成功 <b class="num">{{ points[hovered].success }}</b></span>
          <span><i class="sw bad" />失败 <b class="num">{{ points[hovered].failure }}</b></span>
        </div>
      </div>
      <div class="axis hm-muted">
        <span v-for="(point, index) in points" :key="point.label" class="tick">
          {{ ticks.has(index) ? point.label : '' }}
        </span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.chart {
  position: relative;
  display: flex;
  flex-direction: column;
}

.legend {
  display: flex;
  gap: 16px;
  justify-content: flex-end;
  font-size: 12px;
  color: var(--hm-muted);
  height: 18px;
  margin-bottom: 6px;
}

.legend b {
  color: var(--hm-text);
  font-weight: 600;
  margin-left: 2px;
}

.sw {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 6px;
  vertical-align: 0;
}

.sw.ok {
  background: var(--hm-chart-ok);
}

.sw.bad {
  background: var(--hm-chart-bad);
}

.plot {
  position: relative;
  margin-left: 34px;
}

.grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.line {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--hm-chart-grid);
}

.line.base {
  bottom: 0;
  border-top: 1px solid var(--hm-border);
}

.y {
  position: absolute;
  left: -34px;
  width: 28px;
  top: -8px;
  text-align: right;
  font-size: 11px;
  color: var(--hm-muted);
}

.bars {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.col {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  min-width: 0;
  transition: opacity 0.12s;
}

.col.on {
  background: linear-gradient(180deg, transparent, color-mix(in srgb, var(--el-color-primary) 7%, transparent));
  border-radius: 6px 6px 0 0;
}

.col.dim {
  opacity: 0.45;
}

.stack {
  width: 62%;
  max-width: 44px;
  display: flex;
  flex-direction: column;
  border-radius: 5px 5px 2px 2px;
  overflow: hidden;
  min-height: 3px;
}

.seg {
  flex-basis: 0;
}

.seg.ok {
  background: linear-gradient(180deg, var(--hm-chart-ok-top), var(--hm-chart-ok));
}

.seg.bad {
  background: linear-gradient(180deg, var(--hm-chart-bad-top), var(--hm-chart-bad));
}

.stub {
  width: 62%;
  max-width: 44px;
  height: 3px;
  border-radius: 2px;
  background: var(--hm-chart-grid);
}

.tip {
  position: absolute;
  top: -6px;
  transform: translate(-50%, -100%);
  display: flex;
  flex-direction: column;
  gap: 3px;
  background: var(--hm-tip-bg);
  color: var(--hm-tip-text);
  border: 1px solid var(--hm-tip-border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  white-space: nowrap;
  box-shadow: var(--hm-shadow-lg);
  pointer-events: none;
  z-index: 2;
}

.tip strong {
  color: var(--hm-tip-strong);
  font-weight: 600;
}

.tip b {
  color: var(--hm-tip-strong);
}

.axis {
  display: flex;
  margin-left: 34px;
  margin-top: 8px;
  font-size: 11.5px;
  height: 14px;
}

.tick {
  flex: 1;
  text-align: center;
  white-space: nowrap;
  overflow: visible;
  min-width: 0;
}

.empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
