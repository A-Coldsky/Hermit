<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

const props = defineProps<{ home: string; homeLabel: string }>()
const route = useRoute()
const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="页面不存在" :sub-title="`没有找到 ${route.fullPath}，地址可能写错了，或者页面已经改名。`">
    <template #extra>
      <el-button type="primary" @click="router.push(props.home)">{{ homeLabel }}</el-button>
    </template>
  </el-result>
</template>
