<script setup lang="ts">
import { crabPaths, shellPaths } from '../mascot'

/**
 * Hermit 品牌图标。crab 是钻在海螺壳里的寄居蟹（Crab 控制台），reef 是长着贝壳的珊瑚（Reef 管理端）。
 * 只用 SVG 和 CSS：悬停眨眼、intro 的一次性入场动画都不占脚本，系统要求减少动态效果时不播放。
 */
withDefaults(defineProps<{ variant: 'crab' | 'reef'; size?: number; tile?: boolean; intro?: boolean }>(), {
  size: 32,
  tile: true,
  intro: false,
})
</script>

<template>
  <svg
    class="hm-mark"
    :class="[variant, { intro }]"
    viewBox="0 0 64 64"
    :width="size"
    :height="size"
    aria-hidden="true"
    :data-mark="variant"
  >
    <rect v-if="tile" class="tile" width="64" height="64" rx="14" />
    <template v-if="variant === 'crab'">
      <g class="shell">
        <path class="shell-body" :d="shellPaths.spire" />
        <path class="shell-body" :d="shellPaths.body" />
        <path class="shell-shine" :d="shellPaths.shine" />
        <path class="shell-line" :d="shellPaths.spiral" />
      </g>
      <g class="crab">
        <path class="limb" :d="crabPaths.limbs" />
        <path class="crab-body" :d="crabPaths.body" />
        <path class="crab-body" :d="crabPaths.claw" />
        <g class="eyes">
          <template v-for="eye in crabPaths.eyes" :key="eye.cx">
            <circle class="eye" :cx="eye.cx" :cy="eye.cy" r="3.6" />
            <circle class="pupil" :cx="eye.cx + 0.6" :cy="eye.cy + 0.4" r="1.8" />
          </template>
        </g>
      </g>
    </template>
    <template v-else>
      <path
        class="coral"
        d="M31 55 C31 46 30 40 29 34 C27 28 22 25 19 19 M29 36 C33 30 38 26 40 19 C41 16 43 14 46 11 M40 22 C43 24 45 26 46 30 M30 44 C25 42 19 39 15 33 C14 31 13 29 13 26"
      />
      <path class="seabed" d="M8 55 H56" />
      <g class="shell" transform="translate(26 31) scale(0.5)">
        <path class="shell-body" :d="shellPaths.spire" />
        <path class="shell-body" :d="shellPaths.body" />
        <path class="shell-line" :d="shellPaths.spiral" />
      </g>
      <circle class="bubble b1" cx="52" cy="28" r="2.4" />
      <circle class="bubble b2" cx="55" cy="19" r="1.6" />
    </template>
  </svg>
</template>

<style scoped>
.hm-mark {
  display: block;
  flex-shrink: 0;
  overflow: visible;
}

.tile {
  fill: var(--hm-mark-tile);
}

.shell-body {
  fill: var(--hm-shell);
}

.shell-shine {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 2.2;
  stroke-linecap: round;
}

.shell-line {
  fill: none;
  stroke: var(--hm-shell-line);
  stroke-width: 2.4;
  stroke-linecap: round;
}

.crab-body {
  fill: var(--hm-crab);
}

.limb {
  fill: none;
  stroke: var(--hm-crab);
  stroke-width: 2.6;
  stroke-linecap: round;
}

.eye {
  fill: var(--hm-eye);
}

.pupil {
  fill: var(--hm-pupil);
}

.coral {
  fill: none;
  stroke: var(--hm-coral);
  stroke-width: 5.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.seabed {
  stroke: var(--hm-brand);
  stroke-width: 3;
  stroke-linecap: round;
}

.bubble {
  fill: none;
  stroke: var(--hm-brand-soft);
  stroke-width: 1.5;
}

.eyes,
.crab,
.bubble {
  transform-box: fill-box;
  transform-origin: 50% 100%;
}

.hm-mark.crab:hover .eyes {
  animation: hm-blink 0.36s ease-in-out;
}

.intro .crab {
  animation: hm-peek 0.7s var(--hm-ease) 0.15s backwards;
}

.intro .bubble {
  animation: hm-rise 2.4s ease-out 2 backwards;
}

.intro .b2 {
  animation-delay: 0.6s;
}

@keyframes hm-blink {
  50% {
    transform: scaleY(0.15);
  }
}

@keyframes hm-peek {
  from {
    opacity: 0;
    transform: translate(10px, 4px) scale(0.85);
  }
}

@keyframes hm-rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  30% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .eyes,
  .crab,
  .bubble {
    animation: none !important;
  }
}
</style>
