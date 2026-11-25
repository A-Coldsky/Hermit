<script setup lang="ts">
import MascotCrab from './MascotCrab.vue'
import MascotReef from './MascotReef.vue'
import MascotShell from './MascotShell.vue'

/**
 * Hermit 品牌图标。crab 是背着奶油珍珠海螺的萌芽眼丘小寄居蟹（Crab 控制台），
 * reef 是外展海扇与嵌螺礁丘珊瑚（Reef 管理端）。两者对齐于同一 64×64 光学网格与底基线 y=55.5。
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
    :style="{ width: `${size}px`, height: `${size}px` }"
    aria-hidden="true"
    :data-mark="variant"
  >
    <rect v-if="tile" class="tile" width="64" height="64" rx="14" />
    <template v-if="variant === 'crab'">
      <circle class="bubble b1" cx="10.8" cy="13.2" r="1.9" />
      <circle class="bubble b2" cx="16.4" cy="8.8" r="1.35" />
      <MascotShell />
      <g class="peek"><MascotCrab /></g>
    </template>
    <MascotReef v-else />
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

.bubble {
  fill: none;
  stroke-linecap: round;
}

.bubble.b1 {
  stroke: var(--hm-reef-highlight);
  stroke-width: 1.5;
}

.bubble.b2 {
  stroke: var(--hm-reef-gold-soft);
  stroke-width: 1.4;
}

.hm-mark :deep(.eyes),
.peek,
.bubble,
.hm-mark :deep(.bubble) {
  transform-box: fill-box;
  transform-origin: 50% 60%;
}

.hm-mark:hover :deep(.eyes) {
  animation: hm-blink 0.36s ease-in-out;
}

.intro .peek {
  animation: hm-peek 0.7s var(--hm-ease) 0.15s backwards;
}

.intro .bubble,
.intro :deep(.bubble) {
  animation: hm-rise 2.4s ease-out 2 backwards;
}

.intro .b2,
.intro :deep(.b2) {
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
    transform: translateY(8px) scale(0.92);
  }
}

@keyframes hm-rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  30% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hm-mark :deep(.eyes),
  .peek,
  .bubble,
  .hm-mark :deep(.bubble) {
    animation: none !important;
  }
}
</style>
