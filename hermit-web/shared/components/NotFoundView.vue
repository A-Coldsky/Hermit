<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import HermitMark from './HermitMark.vue'

const props = withDefaults(defineProps<{ home: string; homeLabel: string; mark?: 'crab' | 'reef' }>(), {
  mark: 'crab',
})
const route = useRoute()
const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="页面不存在" :sub-title="`没有找到 ${route.fullPath}，地址可能写错了，或者页面已经改名。`">
    <template #icon>
      <HermitMark :variant="props.mark" :size="88" :tile="false" intro />
    </template>
    <template #extra>
      <el-button type="primary" @click="router.push(props.home)">{{ homeLabel }}</el-button>
    </template>
  </el-result>
</template>
