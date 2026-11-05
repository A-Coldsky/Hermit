<script setup lang="ts">
import { computed } from 'vue'
import { isMcp } from '../labels'

const props = defineProps<{ driver: string | null | undefined; size?: 'small' | 'default' }>()
const mcp = computed(() => isMcp({ driver: props.driver }))
</script>

<template>
  <el-tag
    :type="mcp ? 'primary' : 'info'"
    :effect="mcp ? 'dark' : 'plain'"
    :size="size ?? 'small'"
    round
    :title="mcp ? '工具由源站 MCP 服务提供，Hermit 只做权限管理和转发' : '能力由 Hermit 规则包按步骤调用老系统'"
    data-test="driver-badge"
  >
    {{ mcp ? '原生 MCP · 源站提供' : 'HTTP 规则 · Hermit 编写' }}
  </el-tag>
</template>
