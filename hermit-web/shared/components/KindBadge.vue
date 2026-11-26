<script setup lang="ts">
/**
 * 规则包类型徽标：螺壳和类型名绑在一起。默认是单色小螺壳，用在卡片和列表行；
 * `large` 是 22px 彩色螺壳，用在详情页标题。文字默认是类型名，可以用插槽换成更长的说明。
 */
import { computed } from 'vue'
import KindShell, { type ShellKind } from './KindShell.vue'

const props = defineProps<{ kind?: string | null; large?: boolean }>()

const shape = computed<ShellKind>(() => (props.kind === 'mcp' || props.kind === 'auth' ? props.kind : 'http'))
const label = computed(() => ({ http: 'HTTP 规则', mcp: '原生 MCP', auth: '授权器' })[shape.value])
</script>

<template>
  <span class="kind-badge" :class="[`is-${shape}`, { large }]" :data-kind="shape" data-test="kind-badge">
    <KindShell :kind="shape" :size="large ? 22 : 13" :mono="!large" />
    <span><slot>{{ label }}</slot></span>
  </span>
</template>

<style scoped>
.kind-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
  vertical-align: middle;
  color: var(--kind-fg);
  background: var(--kind-bg);
  --kind-hole: var(--kind-bg);
}

.large {
  gap: 5px;
  padding: 2px 10px 2px 5px;
  border-radius: 14px;
  font-size: 12.5px;
  line-height: 22px;
}

.is-http {
  --kind-fg: var(--hm-kind-http);
  --kind-bg: var(--hm-kind-http-bg);
}

.is-mcp {
  --kind-fg: var(--hm-kind-mcp);
  --kind-bg: var(--hm-kind-mcp-bg);
}

.is-auth {
  --kind-fg: var(--hm-kind-auth);
  --kind-bg: var(--hm-kind-auth-bg);
}
</style>
