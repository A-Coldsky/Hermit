<script setup lang="ts">
/** 螺壳，画在 64×64 坐标里（占上半部分）。brand 是正在用的青绿壳，alt 是备选的琥珀壳，old 是旧壳。 */
withDefaults(defineProps<{ tone?: 'brand' | 'alt' | 'old' }>(), { tone: 'brand' })
</script>

<template>
  <g class="hm-shell" :class="tone">
    <circle class="deep" cx="32" cy="6.5" r="3.5" />
    <ellipse class="base" cx="32" cy="12.5" rx="8.5" ry="6.5" />
    <ellipse class="deep" cx="32" cy="21" rx="14.5" ry="9.5" />
    <ellipse class="base" cx="32" cy="32" rx="20.5" ry="13" />
    <path class="shine" d="M17 27q5-5 12-5" />
  </g>
</template>

<style scoped>
.base {
  fill: var(--hm-shell);
}

.deep {
  fill: var(--hm-shell-deep);
}

.shine {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 2.6;
  stroke-linecap: round;
}

.alt .base {
  fill: var(--hm-shell-alt);
}

.alt .deep {
  fill: var(--hm-shell-alt-deep);
}

.alt .shine {
  stroke: var(--hm-shell-alt-shine);
}

.old .base {
  fill: var(--hm-shell-old);
}

.old .deep {
  fill: var(--hm-shell-old-deep);
}

.old .shine {
  stroke: var(--hm-shell-old-shine);
}
</style>
