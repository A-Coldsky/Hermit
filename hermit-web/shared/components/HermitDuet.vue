<script setup lang="ts">
import { computed } from 'vue'
import MascotCrab from './MascotCrab.vue'
import MascotReef from './MascotReef.vue'
import MascotShell from './MascotShell.vue'

/**
 * Hermit 品牌双角色合影（Reef 外展海扇珊瑚 × Crab 奶油珍珠寄居蟹）。
 * - scene：横版「珊瑚家园合影」，视线齐平，适合连接 Reef、下载 Crab、空态引导等页头/状态区。
 * - badge：1:1「联合方章」，适合项目徽章或紧凑品牌标识。
 * status 仅在 scene 下生效：connected 显示青绿连接波点，disconnected 淡化背景，error 显示告警点。
 */
const props = withDefaults(
  defineProps<{
    variant?: 'scene' | 'badge'
    size?: number
    status?: 'idle' | 'connected' | 'disconnected' | 'error'
    intro?: boolean
  }>(),
  {
    variant: 'scene',
    size: 96,
    status: 'idle',
    intro: false,
  },
)

const width = computed(() => (props.variant === 'badge' ? props.size : Math.round((props.size * 220) / 102)))
const height = computed(() => props.size)
</script>

<template>
  <svg
    class="hm-duet"
    :class="[variant, `status-${status}`, { intro }]"
    :viewBox="variant === 'badge' ? '0 0 128 128' : '0 0 220 102'"
    :width="width"
    :height="height"
    :style="{ width: `${width}px`, height: `${height}px` }"
    aria-hidden="true"
    :data-duet="variant"
  >
    <template v-if="variant === 'badge'">
      <rect class="tile" x="4" y="4" width="120" height="120" rx="28" />
      <rect class="tile-rim" x="7" y="7" width="114" height="114" rx="25" />
      <circle class="badge-glow" cx="64" cy="64" r="44" />
      <g transform="translate(3 31.2) scale(1.18)"><MascotReef /></g>
      <g transform="translate(65 44.5) scale(0.94)">
        <g class="hop">
          <MascotShell />
          <MascotCrab />
        </g>
      </g>
      <path class="seabed badge-seabed" d="M16 97.2 H112" />
    </template>

    <template v-else>
      <circle class="water" cx="110" cy="52" r="43" />
      <g class="reef-group" transform="translate(22 13.5) scale(1.30)">
        <MascotReef />
      </g>
      <g v-if="status === 'connected'" class="link-dots ok">
        <circle cx="97" cy="64" r="2.2" class="d1" />
        <circle cx="107" cy="60.5" r="2.6" class="d2" />
        <circle cx="117" cy="64" r="2.2" class="d3" />
      </g>
      <g v-else-if="status === 'disconnected'" class="link-dots muted">
        <circle cx="99" cy="64" r="1.8" />
        <circle cx="108" cy="64" r="1.8" />
        <circle cx="117" cy="64" r="1.8" />
      </g>
      <g v-else-if="status === 'error'" class="link-dots err">
        <path d="M104 59 L112 67 M112 59 L104 67" />
      </g>
      <g transform="translate(120 32.9) scale(0.95)">
        <g class="hop">
          <MascotShell />
          <MascotCrab />
        </g>
      </g>
      <path class="seabed" d="M14 86 H206" />
    </template>
  </svg>
</template>

<style scoped>
.hm-duet {
  display: block;
  flex-shrink: 0;
  overflow: visible;
}

.tile {
  fill: var(--hm-mark-tile);
}

.tile-rim {
  fill: none;
  stroke: var(--hm-reef-dome);
  stroke-width: 1.5;
  opacity: 0.24;
}

.badge-glow {
  fill: var(--hm-reef-dome);
  opacity: 0.09;
}

.water {
  fill: var(--hm-duet-water);
}

.seabed {
  stroke: var(--hm-brand);
  stroke-width: 2.8;
  stroke-linecap: round;
}

.badge-seabed {
  stroke-width: 3.2;
}

.status-disconnected .water {
  opacity: 0.55;
}

.status-disconnected .reef-group {
  opacity: 0.72;
}

.status-disconnected .seabed {
  stroke: var(--hm-border);
}

.link-dots.ok circle {
  fill: var(--hm-brand);
  opacity: 0.75;
}

.link-dots.muted circle {
  fill: var(--hm-muted);
  opacity: 0.4;
}

.link-dots.err path {
  fill: none;
  stroke: var(--hm-danger);
  stroke-width: 2.4;
  stroke-linecap: round;
}

.hm-duet :deep(.eyes),
.hm-duet :deep(.bubble),
.hop {
  transform-box: fill-box;
  transform-origin: 50% 60%;
}

.hm-duet:hover :deep(.eyes) {
  animation: hm-duet-blink 0.36s ease-in-out;
}

.intro .hop {
  animation: hm-duet-hop 0.6s var(--hm-ease) 0.1s backwards;
}

.status-connected .d1 {
  animation: hm-duet-pulse 2.2s ease-in-out infinite;
}

.status-connected .d2 {
  animation: hm-duet-pulse 2.2s ease-in-out 0.3s infinite;
}

.status-connected .d3 {
  animation: hm-duet-pulse 2.2s ease-in-out 0.6s infinite;
}

@keyframes hm-duet-blink {
  50% {
    transform: scaleY(0.12);
  }
}

@keyframes hm-duet-hop {
  from {
    opacity: 0;
    transform: translateY(7px) scale(0.94);
  }
}

@keyframes hm-duet-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hm-duet :deep(.eyes),
  .hm-duet :deep(.bubble),
  .hop,
  .link-dots circle {
    animation: none !important;
  }
}
</style>
