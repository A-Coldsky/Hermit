<script setup lang="ts">
/**
 * 奶油珍珠斜尖海螺，画在 64×64 光学网格内（顶高 y=7.8，底缘 y=49.4，与 MascotCrab / MascotReef 对齐）。
 * 只输出 <g>，供 HermitMark、HermitDuet、ShellSwapLoader 和 ShellPicker 复用。
 * brand 是默认奶油珍珠壳，alt 是备选海盐晴蓝壳，old 是旧灰壳。
 */
withDefaults(defineProps<{ tone?: 'brand' | 'alt' | 'old' }>(), { tone: 'brand' })
</script>

<template>
  <g class="hm-shell" :class="`tone-${tone}`">
    <path class="deep" d="M37.2 13.6 L50.4 8.0 C52.3 7.2 53.8 8.7 52.7 10.6 L46.2 22.4 Z" />
    <path class="mid" d="M22.2 22.0 C26.8 12.4 38.4 10.6 47.2 14.3 C50.9 16.0 50.3 23.3 44.4 28.2 L20.4 27.7 Z" />
    <path class="base" d="M11.6 39.4 C10.0 24.8 21.0 16.8 35.6 18.6 C48.4 20.1 54.2 29.8 50.3 41.3 C46.4 49.0 16.8 49.4 11.6 39.4 Z" />
    <path class="shade" d="M46.2 23.5 C50.6 27.7 51.6 35.0 48.4 40.8 C46.0 45.2 41.0 46.7 35.8 46.9 C42.8 44.6 46.7 38.0 45.7 29.6 Z" />
    <path class="ridge" d="M16.0 35.2 C20.4 24.9 30.4 20.2 44.1 21.2" />
    <path class="ridge thin" d="M32.1 16.0 C37.5 13.0 43.1 12.8 47.6 14.9" />
    <path class="shine" d="M16.5 27.7 C19.1 22.2 25.0 19.5 31.7 20.1" />
    <circle class="spark" cx="38.2" cy="15.2" r="1.15" />
    <ellipse class="inner" cx="31.6" cy="42.7" rx="15.9" ry="7.9" />
  </g>
</template>

<style scoped>
.base {
  fill: var(--hm-shell);
}

.mid {
  fill: var(--hm-shell-mid);
}

.shade {
  fill: var(--hm-shell-mid);
  opacity: 0.65;
}

.deep {
  fill: var(--hm-shell-deep);
}

.ridge {
  fill: none;
  stroke: var(--hm-shell-ridge);
  stroke-width: 1.9;
  stroke-linecap: round;
}

.ridge.thin {
  stroke-width: 1.6;
}

.shine {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 2.5;
  stroke-linecap: round;
}

.spark {
  fill: var(--hm-shell-shine);
  opacity: 0.88;
}

.inner {
  fill: var(--hm-shell-inner);
  opacity: 0.52;
}

.tone-alt .base {
  fill: var(--hm-shell-alt);
}

.tone-alt .mid,
.tone-alt .shade {
  fill: var(--hm-shell-alt-mid);
}

.tone-alt .deep {
  fill: var(--hm-shell-alt-deep);
}

.tone-alt .ridge {
  stroke: var(--hm-shell-alt-ridge);
}

.tone-alt .shine {
  stroke: var(--hm-shell-alt-shine);
}

.tone-alt .spark {
  fill: var(--hm-shell-alt-shine);
}

.tone-alt .inner {
  fill: var(--hm-shell-alt-inner);
}

.tone-old .base {
  fill: var(--hm-shell-old);
}

.tone-old .mid,
.tone-old .shade {
  fill: var(--hm-shell-old-mid);
}

.tone-old .deep {
  fill: var(--hm-shell-old-deep);
}

.tone-old .ridge {
  stroke: var(--hm-shell-old-ridge);
}

.tone-old .shine {
  stroke: var(--hm-shell-old-shine);
}

.tone-old .spark {
  fill: var(--hm-shell-old-shine);
}

.tone-old .inner {
  fill: var(--hm-shell-old-inner);
}
</style>
