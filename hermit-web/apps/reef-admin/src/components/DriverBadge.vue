<script setup lang="ts">
import { computed } from 'vue'
import KindShell from '@shared/components/KindShell.vue'
import { isMcp } from '../labels'

const props = defineProps<{ driver: string | null | undefined; size?: 'small' | 'default' }>()
const mcp = computed(() => isMcp({ driver: props.driver }))
const auth = computed(() => props.driver === 'auth')
</script>

<template>
  <el-tag
    :type="mcp ? 'primary' : auth ? 'warning' : 'info'"
    :effect="mcp ? 'dark' : 'plain'"
    :size="size ?? 'small'"
    round
    :title="mcp ? '工具由源站 MCP 服务提供，Hermit 只做权限管理和转发' : auth ? '只负责登录，没有能力；别的规则包用 auth.use 引用它' : '能力由 Hermit 规则包按步骤调用老系统'"
    data-test="driver-badge"
  >
    <KindShell :kind="driver" :size="size === 'default' ? 18 : 16" class="shell" />
    {{ mcp ? '原生 MCP · 源站提供' : auth ? '授权器 · 只负责登录' : 'HTTP 规则 · Hermit 编写' }}
  </el-tag>
</template>

<style scoped>
.shell {
  margin-right: 4px;
  vertical-align: -3px;
}
</style>
