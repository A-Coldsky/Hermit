<script setup lang="ts">
/**
 * 吉祥物小寄居蟹（不带壳），画在 64×64 光学网格内（底基线严格落于 y=55.5，安全边距 x=5.6..58.2）。
 * 萌芽眼丘 + 一大一小月牙蟹钳 + ω 微笑，只输出 <g>，放进外层 svg 使用。
 */
</script>

<template>
  <g class="hm-crab">
    <ellipse class="foot" cx="19.4" cy="53.6" rx="3.4" ry="2.0" transform="rotate(-20 19.4 53.6)" />
    <ellipse class="foot" cx="25.8" cy="53.7" rx="2.9" ry="1.8" />
    <ellipse class="foot" cx="44.6" cy="53.6" rx="3.4" ry="2.0" transform="rotate(20 44.6 53.6)" />
    <ellipse class="foot" cx="38.2" cy="53.7" rx="2.9" ry="1.8" />
    <path
      class="crab-body"
      d="M13.6 46.0 C7.2 45.8 4.8 40.0 7.6 34.6 C9.3 31.4 13.2 32.3 12.5 36.6 L15.6 34.6 C17.9 33.3 20.1 35.5 19.4 38.9 C18.6 42.6 16.4 46.2 13.6 46.0 Z"
    />
    <path class="claw-shine" d="M8.9 39.0 C8.4 37.0 9.1 35.3 10.2 34.6" />
    <path
      class="crab-body"
      d="M50.4 46.0 C55.9 45.8 58.0 40.9 55.7 36.2 C54.2 33.6 51.0 34.3 51.6 37.9 L49.0 36.2 C47.1 35.1 45.2 37.0 45.8 39.8 C46.4 42.8 48.2 46.0 50.4 46.0 Z"
    />
    <path
      class="crab-body"
      d="M17.4 47.4 C17.4 40.6 21.6 38.9 24.9 39.2 C28.2 39.5 35.8 39.5 39.1 39.2 C42.4 38.9 46.6 40.6 46.6 47.4 C46.6 52.6 41.9 54.5 32 54.5 C22.1 54.5 17.4 52.6 17.4 47.4 Z"
    />
    <circle class="crab-body" cx="24.6" cy="39.8" r="4.7" />
    <circle class="crab-body" cx="39.4" cy="39.8" r="4.7" />
    <g class="eyes">
      <circle class="pupil" cx="24.6" cy="40.0" r="2.8" />
      <circle class="pupil" cx="39.4" cy="40.0" r="2.8" />
      <circle class="eye" cx="25.4" cy="39.0" r="0.95" />
      <circle class="eye" cx="40.2" cy="39.0" r="0.95" />
    </g>
    <ellipse class="blush" cx="21.2" cy="47.3" rx="3.0" ry="1.7" />
    <ellipse class="blush" cx="42.8" cy="47.3" rx="3.0" ry="1.7" />
    <path class="smile" d="M29.3 46.2 Q30.65 48.1 32 46.6 Q33.35 48.1 34.7 46.2" />
  </g>
</template>

<style scoped>
.foot {
  fill: var(--hm-crab-deep);
}

.crab-body {
  fill: var(--hm-crab);
}

.claw-shine {
  fill: none;
  stroke: var(--hm-crab-soft);
  stroke-width: 1.45;
  stroke-linecap: round;
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
  stroke-width: 1.75;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
