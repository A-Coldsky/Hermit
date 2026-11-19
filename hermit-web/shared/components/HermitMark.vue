<script setup lang="ts">
import MascotCrab from './MascotCrab.vue'
import MascotShell from './MascotShell.vue'

/**
 * Hermit 品牌图标。crab 是背着螺壳的小寄居蟹（Crab 控制台），reef 是一丛珊瑚（Reef 管理端）。
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
      <MascotShell />
      <g class="peek"><MascotCrab /></g>
    </template>
    <template v-else>
      <rect class="coral-deep" x="13" y="23" width="11" height="27" rx="5.5" />
      <rect class="coral-deep" x="40" y="19" width="11" height="31" rx="5.5" />
      <rect class="coral" x="26" y="9" width="12" height="41" rx="6" />
      <rect class="coral" x="13" y="35" width="38" height="18" rx="9" />
      <g class="eyes">
        <circle class="pupil" cx="27" cy="43" r="2.3" />
        <circle class="pupil" cx="37" cy="43" r="2.3" />
      </g>
      <path class="smile" d="M29.5 47.5q2.5 2.3 5 0" />
      <ellipse class="blush" cx="22.5" cy="46.5" rx="2.6" ry="1.6" />
      <ellipse class="blush" cx="41.5" cy="46.5" rx="2.6" ry="1.6" />
      <circle class="bubble b1" cx="54" cy="12" r="2.4" />
      <circle class="bubble b2" cx="10" cy="15" r="1.7" />
      <path class="seabed" d="M8 56H56" />
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

.coral {
  fill: var(--hm-shell);
}

.coral-deep {
  fill: var(--hm-shell-deep);
}

.pupil {
  fill: var(--hm-pupil);
}

.smile {
  fill: none;
  stroke: var(--hm-pupil);
  stroke-width: 1.7;
  stroke-linecap: round;
}

.blush {
  fill: var(--hm-shell-shine);
  opacity: 0.8;
}

.bubble {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 1.6;
}

.seabed {
  stroke: var(--hm-brand);
  stroke-width: 3;
  stroke-linecap: round;
}

.eyes,
.hm-mark :deep(.eyes),
.peek,
.bubble {
  transform-box: fill-box;
  transform-origin: 50% 60%;
}

.hm-mark:hover .eyes,
.hm-mark:hover :deep(.eyes) {
  animation: hm-blink 0.36s ease-in-out;
}

.intro .peek {
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
    transform: scaleY(0.12);
  }
}

@keyframes hm-peek {
  from {
    opacity: 0;
    transform: translateY(9px) scale(0.9);
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
  .hm-mark :deep(.eyes),
  .peek,
  .bubble {
    animation: none !important;
  }
}
</style>
