<script setup lang="ts">
import { crabPaths, shellPaths } from '@shared/mascot'

/**
 * 开发者模式的小插画：寄居蟹挨个掂量三只壳。播两轮就停在原地，鼠标移上去再继续，
 * 不会在打开开发者模式的整段时间里一直占用渲染。
 */
const shells = [
  { x: 30, tone: 'amber' },
  { x: 50, tone: 'mint' },
  { x: 70, tone: 'old' },
] as const
</script>

<template>
  <span class="shell-picker" data-test="shell-picker" title="开发者模式：挑一只合适的壳">
    <svg viewBox="0 0 92 30" width="92" height="30" aria-hidden="true">
      <path class="ground" d="M2 28 H90" />
      <g v-for="(shell, index) in shells" :key="shell.x" class="pick" :class="[`s${index}`, shell.tone]">
        <g :transform="`translate(${shell.x} 12.6) scale(0.32)`">
          <path class="body" :d="shellPaths.spire" />
          <path class="body" :d="shellPaths.body" />
          <path class="line" :d="shellPaths.spiral" />
        </g>
      </g>
      <g class="walker">
        <g transform="translate(0 8.4) scale(0.36)">
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
  </span>
</template>

<style scoped>
.shell-picker {
  display: inline-flex;
  align-items: center;
}

svg {
  overflow: visible;
}

.ground {
  stroke: var(--hm-border);
  stroke-width: 1.2;
  stroke-linecap: round;
}

.amber .body {
  fill: var(--hm-shell);
}

.amber .line {
  stroke: var(--hm-shell-line);
}

.mint .body {
  fill: var(--hm-shell-alt);
}

.mint .line {
  stroke: var(--hm-shell-alt-line);
}

.old .body {
  fill: var(--hm-shell-old);
}

.old .line {
  stroke: var(--hm-shell-old-line);
}

.line {
  fill: none;
  stroke-width: 3;
  stroke-linecap: round;
}

.crab {
  fill: var(--hm-crab);
}

.limb {
  fill: none;
  stroke: var(--hm-crab);
  stroke-width: 2.8;
  stroke-linecap: round;
}

.eye {
  fill: var(--hm-eye);
}

.pupil {
  fill: var(--hm-pupil);
}

.walker,
.pick {
  transform-box: fill-box;
  transform-origin: 50% 100%;
  animation-duration: 7s;
  animation-timing-function: ease-in-out;
  animation-iteration-count: 2;
  animation-fill-mode: both;
}

.shell-picker:hover .walker,
.shell-picker:hover .pick {
  animation-iteration-count: infinite;
}

.walker {
  animation-name: hm-pick-walk;
}

.s0 {
  animation-name: hm-pick-s0;
}

.s1 {
  animation-name: hm-pick-s1;
}

.s2 {
  animation-name: hm-pick-s2;
}

@keyframes hm-pick-walk {
  0%,
  4% {
    transform: translateX(0);
  }
  12%,
  26% {
    transform: translateX(22px);
  }
  34%,
  48% {
    transform: translateX(42px);
  }
  56%,
  70% {
    transform: translateX(62px);
  }
  80%,
  90% {
    transform: translateX(42px);
  }
  100% {
    transform: translateX(0);
  }
}

@keyframes hm-pick-s0 {
  0%,
  13%,
  25%,
  100% {
    transform: none;
  }
  17%,
  21% {
    transform: translateY(-3px) rotate(-10deg);
  }
}

@keyframes hm-pick-s1 {
  0%,
  35%,
  47%,
  81%,
  89%,
  100% {
    transform: none;
  }
  39%,
  43% {
    transform: translateY(-3px) rotate(-10deg);
  }
  84% {
    transform: translateY(-2px);
  }
  86% {
    transform: none;
  }
  88% {
    transform: translateY(-2px);
  }
}

@keyframes hm-pick-s2 {
  0%,
  57%,
  69%,
  100% {
    transform: none;
  }
  61%,
  65% {
    transform: translateY(-3px) rotate(8deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .walker,
  .pick {
    animation: none !important;
  }
}
</style>
