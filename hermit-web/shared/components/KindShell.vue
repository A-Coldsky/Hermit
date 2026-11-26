<script setup lang="ts">
/**
 * 规则包的三种螺壳，按类型区分形状：HTTP 规则是斜尖海螺，原生 MCP 是壳口为插座的鹦鹉螺，
 * 授权器是螺塔带钥匙齿、壳口为钥匙孔的尖塔螺。28px 以下去掉细线，只留轮廓和特征。
 * `mono` 是单色剪影，跟随文字颜色，用在类型徽标这类 16px 以下的地方；壳口用 `--kind-hole` 镂空（默认是页面底色）。
 */
import { computed } from 'vue'
import MascotShell from './MascotShell.vue'

export type ShellKind = 'http' | 'mcp' | 'auth'

const props = withDefaults(defineProps<{ kind?: ShellKind | string | null; size?: number; mono?: boolean }>(),
  { kind: 'http', size: 24, mono: false },
)

const shape = computed<ShellKind>(() => (props.kind === 'mcp' || props.kind === 'auth' ? props.kind : 'http'))
const small = computed(() => props.mono || props.size < 28)
const label = computed(() => ({ http: 'HTTP 规则包', mcp: '原生 MCP', auth: '授权器' })[shape.value])
</script>

<template>
  <svg
    class="kind-shell"
    :class="[`kind-${shape}`, { small, mono }]"
    :width="size"
    :height="size"
    viewBox="4 2 58 56"
    role="img"
    :aria-label="label"
    :data-kind="shape"
  >
    <g v-if="shape === 'http' && mono">
      <path d="M37.2 13.6 L50.4 8.0 C52.3 7.2 53.8 8.7 52.7 10.6 L46.2 22.4 Z" />
      <path d="M22.2 22.0 C26.8 12.4 38.4 10.6 47.2 14.3 C50.9 16.0 50.3 23.3 44.4 28.2 L20.4 27.7 Z" />
      <path d="M11.6 39.4 C10.0 24.8 21.0 16.8 35.6 18.6 C48.4 20.1 54.2 29.8 50.3 41.3 C46.4 49.0 16.8 49.4 11.6 39.4 Z" />
      <ellipse class="cut" cx="31.6" cy="42.7" rx="13.4" ry="5.6" />
    </g>
    <g v-else-if="shape === 'http'"><MascotShell /></g>

    <g v-else-if="shape === 'mcp'">
      <path class="m-mid" d="M36 27 C44 22 56 26 57 37 C58 46 50 50.5 41 49.4 C33 48.4 29 42 31 35 Z" />
      <path class="m-base" d="M8.5 31 C8.5 17.5 19 8 32 8 C44 8 53 16.5 53 28 C53 39 44.5 47.5 32.5 48.5 C19 49.6 8.5 43 8.5 31 Z" />
      <path class="m-deep" d="M14 17.5 C18 12 24.5 8.6 31 8.1 L27 16.5 C23 17 19.5 19 17.5 22 Z" />
      <path class="m-deep soft" d="M36 8.4 C41 9.3 45.5 12 48.6 16 L41 20 C39.6 18.2 37.4 16.8 35 16.2 Z" />
      <path class="m-mid soft" d="M50 20 C53.6 26 53.6 34 49.6 40 C46 45.4 40 48 33.5 48.4 C41 45.4 46 39.5 46.6 32 C46.9 28 46 24.4 44.4 21.6 Z" />
      <template v-if="!small">
        <path class="m-ridge" d="M30 29.5 C30 25.6 35 24.6 37 28.4 C39.4 33 35 38.6 28.6 38 C21 37.2 17 29.6 20.6 23 C24.6 15.6 35.6 14 42.4 19.6" />
        <path class="m-shine" d="M13 30 C13 23.5 16.5 18 22 15" />
        <circle class="m-spark" cx="29.6" cy="30.4" r="1.5" />
      </template>
      <g transform="rotate(-18 45 38.6)">
        <ellipse class="m-socket" cx="45" cy="38.6" :rx="small ? 11.6 : 9.6" :ry="small ? 10 : 8.2" />
        <path class="m-hole" :d="small ? 'M41.4 34.6 L41.4 42.6 M48.6 34.6 L48.6 42.6' : 'M42 35.4 L42 41.8 M48 35.4 L48 41.8'" />
      </g>
    </g>

    <g v-else transform="rotate(14 32 30)">
      <path class="a-deep" d="M32 3.6 C33 3.6 33.8 4.2 34.2 5.2 L38 16 L26 16 L29.8 5.2 C30.2 4.2 31 3.6 32 3.6 Z" />
      <path class="a-deep" d="M36.4 8.4 L42.6 8.4 L42.6 12 L39.6 12 L39.6 14.2 L44.6 14.2 L44.6 18.4 L40.4 18.4 L40.4 21 L46.4 21 L46.4 25.4 L39 25.4 Z" />
      <path class="a-mid" d="M25.4 14.6 C29 12.8 35 12.8 38.6 14.6 L43.4 27.4 L20.6 27.4 Z" />
      <path class="a-base" d="M12.6 39 C12.6 30 21 24.4 32 24.4 C43 24.4 51.4 30 51.4 39 C51.4 48 43 53.4 32 53.4 C21 53.4 12.6 48 12.6 39 Z" />
      <path class="a-mid soft" d="M45 28 C49 30.6 51.4 34.4 51.4 39 C51.4 48 43 53.4 32 53.4 C40 51.4 45.6 46 45.6 38 C45.6 34 45.4 30.6 45 28 Z" />
      <template v-if="!small">
        <path class="a-ridge thin" d="M25.6 15.4 C29.4 17.4 34.6 17.4 38.4 15.4" />
        <path class="a-ridge" d="M19 28.4 C26 31.6 38 31.6 45 28.4" />
        <path class="a-shine" d="M16.6 41 C16.2 36.6 18 33 21.2 30.6" />
      </template>
      <path class="a-rim" d="M32 32.6 A6.3 6.3 0 0 1 36.6 43.2 L38.6 50.6 L25.4 50.6 L27.4 43.2 A6.3 6.3 0 0 1 32 32.6 Z" />
      <path class="a-hole" d="M32 34.4 A4.6 4.6 0 0 1 34.9 42.6 L36.6 49 L27.4 49 L29.1 42.6 A4.6 4.6 0 0 1 32 34.4 Z" />
    </g>
  </svg>
</template>

<style scoped>
.kind-shell {
  flex-shrink: 0;
  vertical-align: middle;
}

.soft {
  opacity: 0.7;
}

.m-base {
  fill: var(--hm-shell-mcp);
}

.m-mid {
  fill: var(--hm-shell-mcp-mid);
}

.m-deep {
  fill: var(--hm-shell-mcp-deep);
}

.m-ridge {
  fill: none;
  stroke: var(--hm-shell-mcp-ridge);
  stroke-width: 1.9;
  stroke-linecap: round;
}

.m-shine {
  fill: none;
  stroke: var(--hm-shell-mcp-shine);
  stroke-width: 2.5;
  stroke-linecap: round;
}

.m-spark {
  fill: var(--hm-shell-mcp-shine);
}

/* 插座的底色很深，深色主题下靠亮边和亮插孔认出来。 */
.m-socket {
  fill: var(--hm-shell-mcp-inner);
  stroke: var(--hm-shell-mcp-shine);
  stroke-width: 1.2;
}

.m-hole {
  fill: none;
  stroke: var(--hm-shell-mcp-shine);
  stroke-width: 2.6;
  stroke-linecap: round;
}

.small .m-hole {
  stroke-width: 3.4;
}

.a-base {
  fill: var(--hm-shell-key);
}

.a-mid {
  fill: var(--hm-shell-key-mid);
}

.a-deep {
  fill: var(--hm-shell-key-deep);
}

.a-ridge {
  fill: none;
  stroke: var(--hm-shell-key-ridge);
  stroke-width: 1.9;
  stroke-linecap: round;
}

.a-ridge.thin {
  stroke-width: 1.6;
}

.a-shine {
  fill: none;
  stroke: var(--hm-shell-key-shine);
  stroke-width: 2.5;
  stroke-linecap: round;
}

.a-rim {
  fill: var(--hm-shell-key-shine);
  opacity: 0.9;
}

.a-hole {
  fill: var(--hm-shell-key-inner);
}

.mono path,
.mono ellipse {
  fill: currentColor;
  stroke: none;
  opacity: 1;
}

.mono .cut,
.mono .m-socket,
.mono .a-hole {
  fill: var(--kind-hole, var(--hm-surface));
}

.mono .a-rim {
  display: none;
}

.mono .m-hole {
  fill: none;
  stroke: currentColor;
  stroke-width: 3.8;
}
</style>
