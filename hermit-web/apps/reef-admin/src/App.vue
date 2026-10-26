<script setup lang="ts">
import { Bell, Box, DataLine, Document, House, Key, SwitchButton } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout, { type NavItem } from '@shared/components/AppLayout.vue'
import { api } from './api'
import { session, signOut } from './auth'
import { openAlerts, refreshOpenAlerts } from './alerts'

const router = useRouter()
const timer = ref<number>()

const items = computed<NavItem[]>(() => [
  { to: '/', label: '概览', icon: House },
  { to: '/conches', label: '规则包目录', icon: Box },
  { to: '/tide', label: 'Tide 统计', icon: DataLine },
  { to: '/alerts', label: '告警', icon: Bell, badge: openAlerts.value },
  { to: '/audit', label: '审计日志', icon: Document },
  { to: '/settings', label: '签名公钥', icon: Key },
])

onMounted(async () => {
  try {
    const me = await api.me()
    session.user = me.username
  } catch {
    // 401 已在客户端里处理
  }
  void refreshOpenAlerts()
  timer.value = window.setInterval(() => void refreshOpenAlerts(), 60_000)
})

onBeforeUnmount(() => window.clearInterval(timer.value))

function logout() {
  signOut()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <AppLayout product="Reef" subtitle="Hermit 管理端" :items="items">
    <template #topbar>
      <span class="hm-muted">{{ session.user }}</span>
      <el-button :icon="SwitchButton" text @click="logout">退出</el-button>
    </template>
    <template #sidebar-foot>
      <span>发布签名规则包 · 回收运行指标</span>
    </template>
  </AppLayout>
</template>
