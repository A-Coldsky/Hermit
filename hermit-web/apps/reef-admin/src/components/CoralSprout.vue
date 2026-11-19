<script setup lang="ts">
import HermitMark from '@shared/components/HermitMark.vue'
import MascotShell from '@shared/components/MascotShell.vue'

/**
 * Reef 上传规则包的状态插画：校验中珊瑚冒泡，发布成功后珊瑚旁长出一只新壳（只播一次）。
 * 只在这两个状态挂载，动画只改 transform 和 opacity。
 */
defineProps<{ state: 'working' | 'done' }>()
</script>

<template>
  <div class="coral-sprout" :class="state" role="status" aria-live="polite" data-test="coral-sprout">
    <div class="art" aria-hidden="true">
      <HermitMark variant="reef" :size="56" :tile="false" />
      <svg viewBox="0 0 96 64" width="84" height="56">
        <template v-if="state === 'done'">
          <path class="seabed" d="M56 56H90" />
          <g transform="translate(62 31) scale(0.5)">
            <g class="sprout"><MascotShell /></g>
          </g>
        </template>
        <circle class="bubble b1" cx="32" cy="4" r="2.2" />
        <circle class="bubble b2" cx="45" cy="12" r="1.6" />
        <circle class="bubble b3" cx="19" cy="16" r="1.8" />
      </svg>
    </div>
    <div class="text"><slot /></div>
  </div>
</template>

<style scoped>
.coral-sprout {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--hm-muted);
  font-size: 13px;
}

.art {
  position: relative;
  flex-shrink: 0;
  width: 84px;
  height: 56px;
}

.art svg:last-child {
  position: absolute;
  inset: 0;
  overflow: visible;
}

.text {
  min-width: 0;
}

.seabed {
  stroke: var(--hm-brand);
  stroke-width: 3;
  stroke-linecap: round;
}

.bubble {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 1.6;
  opacity: 0;
}

.sprout,
.bubble {
  transform-box: fill-box;
  transform-origin: 50% 100%;
}

.working .bubble {
  animation: hm-sprout-rise 2.4s ease-out infinite;
}

.done .bubble {
  animation: hm-sprout-rise 2.4s ease-out 2;
}

.b2 {
  animation-delay: 0.7s !important;
}

.b3 {
  animation-delay: 1.3s !important;
}

.sprout {
  animation: hm-sprout-pop 0.7s var(--hm-ease) 0.1s backwards;
}

@keyframes hm-sprout-rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  30% {
    opacity: 1;
  }
  to {
    opacity: 0;
    transform: translateY(-8px);
  }
}

@keyframes hm-sprout-pop {
  from {
    opacity: 0;
    transform: scale(0.2);
  }
  60% {
    opacity: 1;
    transform: scale(1.12);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sprout,
  .bubble {
    animation: none !important;
  }
}
</style>
