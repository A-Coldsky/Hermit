<script setup lang="ts">
import { Box, DataAnalysis, Document, House, Key, List, Shop } from '@element-plus/icons-vue'
import { computed, onMounted } from 'vue'
import AppLayout, { type NavItem } from '@shared/components/AppLayout.vue'
import { pendingConches, refreshPending } from './status'

const items = computed<NavItem[]>(() => [
  { to: '/', label: '概览', icon: House },
  { to: '/catalog', label: '可订阅规则包', icon: Shop },
  { to: '/conches', label: '规则包与账号', icon: Box, badge: pendingConches.value },
  { to: '/apikeys', label: 'API Key', icon: Key },
  { to: '/calls', label: '调用记录', icon: List },
  { to: '/audit', label: '配置审计', icon: Document },
  { to: '/reports', label: '上报预览', icon: DataAnalysis },
])

onMounted(refreshPending)
</script>

<template>
  <AppLayout product="Crab" subtitle="Hermit 本地控制台" :items="items">
    <template #topbar>
      <el-tag type="info" effect="plain" size="small">仅限本机访问</el-tag>
    </template>
    <template #sidebar-foot>
      <span>凭证只保存在本机，加密存储</span>
    </template>
  </AppLayout>
</template>
