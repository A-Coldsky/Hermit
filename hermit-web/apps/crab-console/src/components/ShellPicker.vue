<script setup lang="ts">
import MascotCrab from '@shared/components/MascotCrab.vue'
import MascotShell from '@shared/components/MascotShell.vue'

/**
 * 开发者模式的小插画：小寄居蟹挨个掂量三只壳，最后挑中中间那只。播两轮就停在原地，
 * 鼠标移上去再继续，不会在打开开发者模式的整段时间里一直占用渲染。
 */
</script>

<template>
  <span class="shell-picker" data-test="shell-picker" title="开发者模式：挑一只合适的壳">
    <svg viewBox="0 0 140 34" width="164" height="40" aria-hidden="true">
      <path class="ground" d="M2 32H138" />
      <g class="pick s0">
        <g transform="translate(38.92 18.2) scale(0.3)"><MascotShell tone="alt" /></g>
      </g>
      <g class="pick s1">
        <g transform="translate(75.4 13.6) scale(0.4)"><MascotShell /></g>
      </g>
      <g class="pick s2">
        <g transform="translate(116.55 18.2) scale(0.3)"><MascotShell tone="old" /></g>
      </g>
      <g class="walker">
        <g class="bob">
          <g transform="translate(0.99 9.82) scale(0.3744)"><MascotCrab /></g>
        </g>
        <text class="mark ask" x="19" y="15">?</text>
        <text class="mark found" x="19" y="15">!</text>
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
  stroke-width: 1.3;
  stroke-linecap: round;
}

.mark {
  font-size: 9px;
  font-weight: 700;
  fill: var(--hm-muted);
}

.found {
  font-size: 10px;
  font-weight: 800;
  fill: var(--hm-shell-alt);
}

.walker,
.bob,
.pick {
  transform-box: fill-box;
  transform-origin: 50% 100%;
}

.walker,
.pick,
.mark {
  animation-duration: 8s;
  animation-timing-function: ease-in-out;
  animation-iteration-count: 2;
  animation-fill-mode: both;
}

.bob {
  animation: hm-pick-bob 0.36s ease-in-out 44 alternate;
}

.shell-picker:hover .walker,
.shell-picker:hover .pick,
.shell-picker:hover .mark,
.shell-picker:hover .bob {
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

.ask {
  animation-name: hm-pick-ask;
}

.found {
  animation-name: hm-pick-found;
}

@keyframes hm-pick-bob {
  to {
    transform: translateY(-1.6px);
  }
}

@keyframes hm-pick-walk {
  0%,
  3% {
    transform: translateX(0);
  }
  11%,
  24% {
    transform: translateX(17px);
  }
  32%,
  45% {
    transform: translateX(55px);
  }
  53%,
  66% {
    transform: translateX(95px);
  }
  76%,
  94% {
    transform: translateX(55px);
  }
  100% {
    transform: translateX(0);
  }
}

@keyframes hm-pick-s0 {
  0%,
  12%,
  24%,
  100% {
    transform: none;
  }
  16%,
  20% {
    transform: translateY(-3px) rotate(-10deg);
  }
}

@keyframes hm-pick-s1 {
  0%,
  33%,
  45%,
  78%,
  92%,
  100% {
    transform: none;
  }
  37%,
  41% {
    transform: translateY(-3px) rotate(-10deg);
  }
  82% {
    transform: translateY(-5px);
  }
  86% {
    transform: none;
  }
  89% {
    transform: translateY(-2.5px);
  }
}

@keyframes hm-pick-s2 {
  0%,
  54%,
  66%,
  100% {
    transform: none;
  }
  58%,
  62% {
    transform: translateY(-3px) rotate(9deg);
  }
}

@keyframes hm-pick-ask {
  0%,
  12%,
  25%,
  33%,
  46%,
  54%,
  67%,
  100% {
    opacity: 0;
  }
  14%,
  23%,
  35%,
  44%,
  56%,
  65% {
    opacity: 1;
  }
}

@keyframes hm-pick-found {
  0%,
  77%,
  93%,
  100% {
    opacity: 0;
  }
  79%,
  91% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .walker,
  .bob,
  .pick,
  .mark {
    animation: none !important;
  }

  .mark {
    opacity: 0;
  }
}
</style>
