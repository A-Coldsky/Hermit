<script setup lang="ts">
import MascotCrab from '@shared/components/MascotCrab.vue'
import MascotShell from '@shared/components/MascotShell.vue'

/**
 * 换壳加载提示：小寄居蟹离开旧壳、跳进新壳。只在等待期间挂载，动画只改 transform 和 opacity。
 * reverse 用于退回上一版：小蟹从右边的新壳走回左边的旧壳。compact 用在表格行里。
 */
defineProps<{ label: string; reverse?: boolean; compact?: boolean }>()
</script>

<template>
  <div class="shell-swap" :class="{ reverse, compact }" role="status" aria-live="polite" data-test="shell-swap">
    <svg viewBox="0 0 120 40" :width="compact ? 84 : 150" :height="compact ? 28 : 50" aria-hidden="true">
      <path class="ground" d="M2 37H118" />
      <g class="from">
        <g transform="translate(2.65 18.65) scale(0.375)"><MascotShell :tone="reverse ? 'brand' : 'old'" /></g>
      </g>
      <g class="to">
        <g transform="translate(86.2 14) scale(0.5)"><MascotShell :tone="reverse ? 'old' : 'brand'" /></g>
      </g>
      <g class="spark">
        <circle cx="86" cy="12" r="1.5" />
        <circle cx="114" cy="10" r="1.8" />
        <circle cx="100" cy="6" r="1.3" />
      </g>
      <g class="walker">
        <g class="bob">
          <g transform="translate(2.6 6.2) scale(0.52)"><MascotCrab /></g>
        </g>
      </g>
    </svg>
    <span class="label">{{ label }}</span>
  </div>
</template>

<style scoped>
.shell-swap {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--hm-muted);
  font-size: 13px;
}

svg {
  flex-shrink: 0;
  overflow: visible;
}

.reverse svg {
  transform: scaleX(-1);
}

.compact {
  gap: 8px;
  font-size: 12px;
}

.ground {
  stroke: var(--hm-border);
  stroke-width: 1.5;
  stroke-linecap: round;
}

.spark {
  fill: var(--hm-shell-alt);
}

.walker,
.bob,
.from,
.to,
.spark {
  transform-box: fill-box;
  transform-origin: 50% 100%;
}

.spark {
  transform-origin: 50% 50%;
  animation: hm-swap-spark 3.2s ease-out infinite;
}

.walker {
  animation: hm-swap-walk 3.2s ease-in-out infinite;
}

.bob {
  animation: hm-swap-bob 0.32s ease-in-out infinite alternate;
}

.from {
  animation: hm-swap-old 3.2s ease-in-out infinite;
}

.to {
  animation: hm-swap-hop 3.2s ease-in-out infinite;
}

@keyframes hm-swap-bob {
  to {
    transform: translateY(-1.6px);
  }
}

@keyframes hm-swap-walk {
  0%,
  6% {
    opacity: 0;
    transform: translate(0, 3px) scale(0.7);
  }
  14% {
    opacity: 1;
    transform: translate(8px, 0) scale(1);
  }
  62% {
    opacity: 1;
    transform: translate(70px, 0) scale(1);
  }
  68% {
    opacity: 1;
    transform: translate(78px, -9px) scale(0.9);
  }
  74%,
  100% {
    opacity: 0;
    transform: translate(84px, -2px) scale(0.5);
  }
}

@keyframes hm-swap-old {
  0%,
  12%,
  100% {
    transform: none;
  }
  4% {
    transform: rotate(-8deg);
  }
  8% {
    transform: rotate(6deg);
  }
}

@keyframes hm-swap-hop {
  0%,
  72%,
  92%,
  100% {
    transform: none;
  }
  78% {
    transform: translateY(-5px) scale(0.96, 1.05);
  }
  84% {
    transform: scale(1.06, 0.92);
  }
  88% {
    transform: translateY(-2px);
  }
}

@keyframes hm-swap-spark {
  0%,
  76% {
    opacity: 0;
    transform: scale(0.3);
  }
  84% {
    opacity: 1;
    transform: scale(1);
  }
  96%,
  100% {
    opacity: 0;
    transform: scale(1.3);
  }
}

@media (prefers-reduced-motion: reduce) {
  .walker,
  .bob,
  .from,
  .to,
  .spark {
    animation: none !important;
  }

  .walker {
    transform: translate(40px, 0);
  }

  .spark {
    opacity: 0;
  }
}
</style>
