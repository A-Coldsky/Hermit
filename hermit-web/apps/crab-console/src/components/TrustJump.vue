<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { trustLocation } from '../tls'

const props = defineProps<{
  message?: string | null
  kind?: string | null
  host?: string | null
  port?: number | null
}>()

const router = useRouter()
const to = computed(() =>
  trustLocation({ message: props.message, kind: props.kind, host: props.host, port: props.port }),
)

function go(event: Event) {
  event.stopPropagation()
  if (to.value) void router.push(to.value)
}
</script>

<template>
  <el-button v-if="to" link type="primary" data-test="go-trust" @click="go">去信任</el-button>
</template>
