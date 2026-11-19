<script setup lang="ts">
import { crabPaths, shellPaths } from '@shared/mascot'

/** 同步时的加载提示：寄居蟹离开旧壳、钻进新壳。只在同步进行中挂载，动画只改 transform 和 opacity。 */
defineProps<{ label: string }>()
</script>

<template>
  <div class="shell-swap" role="status" aria-live="polite" data-test="shell-swap">
    <svg viewBox="0 0 120 40" width="132" height="44" aria-hidden="true">
      <path class="ground" d="M2 37 H118" />
      <g transform="translate(-6 12) scale(0.5)">
        <path class="old" :d="shellPaths.spire" />
        <path class="old" :d="shellPaths.body" />
        <path class="old-line" :d="shellPaths.spiral" />
      </g>
      <g class="new">
        <g transform="translate(72 8) scale(0.6)">
          <path class="shell" :d="shellPaths.spire" />
          <path class="shell" :d="shellPaths.body" />
          <path class="shine" :d="shellPaths.shine" />
          <path class="line" :d="shellPaths.spiral" />
        </g>
      </g>
      <g class="walker">
        <g transform="translate(4 10) scale(0.5)">
          <path class="limb" :d="crabPaths.limbs" />
          <path class="crab" :d="crabPaths.body" />
          <path class="crab" :d="crabPaths.claw" />
          <template v-for="eye in crabPaths.eyes" :key="eye.cx">
            <circle class="eye" :cx="eye.cx" :cy="eye.cy" r="3.6" />
            <circle class="pupil" :cx="eye.cx + 0.6" :cy="eye.cy + 0.4" r="1.8" />
          </template>
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
  gap: 10px;
  color: var(--hm-muted);
  font-size: 13px;
}

svg {
  flex-shrink: 0;
  overflow: visible;
}

.ground {
  stroke: var(--hm-border);
  stroke-width: 1.5;
  stroke-linecap: round;
}

.old {
  fill: var(--hm-shell-old);
}

.old-line {
  fill: none;
  stroke: var(--hm-shell-old-line);
  stroke-width: 2.4;
  stroke-linecap: round;
}

.shell {
  fill: var(--hm-shell);
}

.shine {
  fill: none;
  stroke: var(--hm-shell-shine);
  stroke-width: 2.2;
  stroke-linecap: round;
}

.line {
  fill: none;
  stroke: var(--hm-shell-line);
  stroke-width: 2.4;
  stroke-linecap: round;
}

.crab {
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

.walker,
.new {
  transform-box: fill-box;
  transform-origin: 50% 100%;
}

.walker {
  animation: hm-swap-walk 2.6s ease-in-out infinite;
}

.new {
  animation: hm-swap-wiggle 2.6s ease-in-out infinite;
}

@keyframes hm-swap-walk {
  0% {
    opacity: 0;
    transform: translate(0, 5px);
  }
  12% {
    opacity: 1;
    transform: translate(0, 0);
  }
  20% {
    transform: translate(8px, -1.5px);
  }
  28% {
    transform: translate(17px, 0);
  }
  36% {
    transform: translate(26px, -1.5px);
  }
  44% {
    transform: translate(35px, 0);
  }
  52% {
    transform: translate(44px, -1.5px);
  }
  60% {
    transform: translate(53px, 0);
  }
  68% {
    opacity: 1;
    transform: translate(61px, 0);
  }
  78%,
  100% {
    opacity: 0;
    transform: translate(68px, 3px) scale(0.8);
  }
}

@keyframes hm-swap-wiggle {
  0%,
  76%,
  96%,
  100% {
    transform: rotate(0);
  }
  82% {
    transform: rotate(-7deg);
  }
  89% {
    transform: rotate(5deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .walker,
  .new {
    animation: none !important;
  }

  .walker {
    transform: translate(40px, 0);
  }
}
</style>
