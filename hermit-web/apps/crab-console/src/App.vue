<script setup lang="ts">
import { Box, DataAnalysis, House, Key, List } from '@element-plus/icons-vue'
import { computed, onMounted } from 'vue'
import AppLayout, { type NavItem } from '@shared/components/AppLayout.vue'
import { pendingShells, refreshPending } from './status'

const items = computed<NavItem[]>(() => [
  { to: '/', label: '概览', icon: House },
  { to: '/shells', label: 'Shell 与账号', icon: Box, badge: pendingShells.value },
  { to: '/apikeys', label: 'API Key', icon: Key },
  { to: '/calls', label: '调用记录', icon: List },
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
