<script setup lang="ts">
/**
 * 吉祥物 Reef 珊瑚礁（外展海扇 + 嵌螺礁），画在 64×64 光学网格内（顶高 y=7.8，底基线严格落于 y=55.5）。
 * 保留初版极简纯点五官，左下缘自然嵌入与 Crab 同色系的奶油珍珠螺室。只输出 <g>，放进外层 svg 使用。
 */
withDefaults(defineProps<{ bubbles?: boolean }>(), { bubbles: true })
</script>

<template>
  <g class="hm-reef">
    <path class="branch-gold" d="M24 38.5 C18 33.5 12 27.5 8.5 20" />
    <path class="branch-gold-deep" d="M16.5 31.5 C11.5 32 7.8 30 6.5 27" />
    <path class="branch-gold mid" d="M19 33.5 C17.5 26.5 17.5 21 19.5 16" />

    <path class="branch-rose" d="M36 37.5 C41 29.5 47.5 23.5 54 18" />
    <path class="branch-rose-deep" d="M44 28 C49.5 28 53.8 26.2 56.2 23.5" />
    <path class="branch-rose mid" d="M41 30 C40.5 22.5 42 16.5 45 12" />

    <path class="coral branch-teal" d="M30 37.5 C28.5 26.5 28 17.5 29.5 10.2" />
    <path class="coral branch-teal sub" d="M29 22.5 C24.5 19 22.5 14.5 23 11" />
    <path class="coral branch-teal-soft" d="M29.5 20 C34 17 36.5 13.5 37 10" />

    <path class="coral dome" d="M12 52 C12 39.5 20 35.5 32 35.5 C44 35.5 52 39.5 52 52 C52 54.3 50.2 55.5 48 55.5 H16 C13.8 55.5 12 54.3 12 52 Z" />
    <path class="dome-shade" d="M52 52 C52 45.5 49.5 41.5 45.5 39 C47.5 44 47.5 50 45 55.5 H48 C50.2 55.5 52 54.3 52 52 Z" />
    <path class="dome-shine" d="M24 38.2 C27.8 37.2 33.2 37.2 37 38.2" />

    <path class="shell-base" d="M21 55.5 C20 47 13.5 43 7.8 45.6 C5.4 46.8 5.6 52.2 9.2 55.5 Z" />
    <path class="shell-mid" d="M19.4 55.5 C19.8 51.6 18 48.2 15.2 46.6 C16.6 49.2 16.6 52.8 15.2 55.5 Z" />
    <path
      class="shell-spiral"
      d="M17.5 53.8 C17.2 48.6 13 46.6 9.8 48.6 C8.3 49.7 8.9 52.4 11.2 53 C13.1 53.5 14.3 52 13.3 50.6"
    />

    <g class="eyes">
      <circle class="pupil" cx="29" cy="45.0" r="2.35" />
      <circle class="pupil" cx="39" cy="45.0" r="2.35" />
    </g>
    <path class="smile" d="M31.5 49.2 q2.5 2.2 5 0" />
    <ellipse class="blush" cx="24.6" cy="48.2" rx="2.6" ry="1.5" />
    <ellipse class="blush" cx="43.4" cy="48.2" rx="2.6" ry="1.5" />

    <template v-if="bubbles">
      <circle class="bubble b1" cx="55.5" cy="11.2" r="1.95" />
      <circle class="bubble b2" cx="10.8" cy="12.5" r="1.45" />
    </template>
  </g>
</template>

<style scoped>
.branch-gold,
.branch-gold-deep,
.branch-rose,
.branch-rose-deep,
.branch-teal,
.branch-teal-soft,
.dome-shine,
.shell-spiral,
.smile,
.bubble {
  fill: none;
  stroke-linecap: round;
}

.branch-gold {
  stroke: var(--hm-reef-gold);
  stroke-width: 4.4;
}

.branch-gold.mid {
  stroke-width: 3.6;
}

.branch-gold-deep {
  stroke: var(--hm-reef-gold-deep);
  stroke-width: 3.4;
}

.branch-rose {
  stroke: var(--hm-reef-rose);
  stroke-width: 4.8;
}

.branch-rose.mid {
  stroke-width: 3.8;
}

.branch-rose-deep {
  stroke: var(--hm-reef-rose-deep);
  stroke-width: 3.5;
}

.branch-teal {
  stroke: var(--hm-reef-dome);
  stroke-width: 4.6;
}

.branch-teal.sub {
  stroke-width: 3.4;
}

.branch-teal-soft {
  stroke: var(--hm-reef-teal-soft);
  stroke-width: 3.3;
}

.dome {
  fill: var(--hm-reef-dome);
}

.dome-shade {
  fill: var(--hm-reef-dome-deep);
  opacity: 0.5;
}

.dome-shine {
  stroke: var(--hm-reef-highlight);
  stroke-width: 1.8;
  opacity: 0.6;
}

.shell-base {
  fill: var(--hm-shell);
}

.shell-mid {
  fill: var(--hm-shell-mid);
  opacity: 0.72;
}

.shell-spiral {
  stroke: var(--hm-shell-ridge);
  stroke-width: 1.65;
}

.pupil {
  fill: var(--hm-pupil);
}

.smile {
  stroke: var(--hm-pupil);
  stroke-width: 1.75;
}

.blush {
  fill: var(--hm-reef-highlight);
  opacity: 0.85;
}

.bubble.b1 {
  stroke: var(--hm-reef-highlight);
  stroke-width: 1.5;
}

.bubble.b2 {
  stroke: var(--hm-reef-gold-soft);
  stroke-width: 1.4;
}
</style>
