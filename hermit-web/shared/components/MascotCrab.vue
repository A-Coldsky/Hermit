<script setup lang="ts">
/** 吉祥物小寄居蟹（不带壳），画在 64×64 坐标里。只输出 <g>，放进外层 svg 使用。 */
</script>

<template>
  <g class="hm-crab">
    <path class="limb" d="M17 44L10 40M47 44L54 40M20 53l-4 4M26 55l-2 4M44 53l4 4M38 55l2 4" />
    <path class="crab-body" d="M9 37L11.4 30.5A7 7 0 1 1 6.6 30.5Z" />
    <path class="crab-body" d="M55 37L57.4 30.5A7 7 0 1 1 52.6 30.5Z" />
    <ellipse class="crab-body" cx="32" cy="45" rx="17" ry="11.5" />
    <ellipse class="blush" cx="21.5" cy="48" rx="3.2" ry="2" />
    <ellipse class="blush" cx="42.5" cy="48" rx="3.2" ry="2" />
    <g class="eyes">
      <circle class="eye" cx="25" cy="37" r="6.4" />
      <circle class="eye" cx="39" cy="37" r="6.4" />
      <circle class="pupil" cx="26" cy="38" r="3.3" />
      <circle class="pupil" cx="38" cy="38" r="3.3" />
      <circle class="eye" cx="27.2" cy="36.6" r="1.1" />
      <circle class="eye" cx="39.2" cy="36.6" r="1.1" />
    </g>
    <path class="smile" d="M29 48.5q3 2.8 6 0" />
  </g>
</template>

<style scoped>
.limb {
  fill: none;
  stroke: var(--hm-crab-deep);
  stroke-width: 3;
  stroke-linecap: round;
}

.crab-body {
  fill: var(--hm-crab);
}

.blush {
  fill: var(--hm-crab-blush);
}

.eye {
  fill: var(--hm-eye);
}

.pupil {
  fill: var(--hm-pupil);
}

.smile {
  fill: none;
  stroke: var(--hm-pupil);
  stroke-width: 1.8;
  stroke-linecap: round;
}
</style>
