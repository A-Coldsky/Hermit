<script setup lang="ts">
import { Box, Connection, DataAnalysis, Document, FolderOpened, House, Key, List, Lock, Shop, Unlock, User, VideoCamera } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import AppLayout, { type NavItem } from '@shared/components/AppLayout.vue'
import PasswordDialog from './components/PasswordDialog.vue'
import { consoleSession } from './session'
import { pendingConches, refreshPending } from './status'

const items = computed<NavItem[]>(() => [
  { to: '/', label: '概览', icon: House },
  { to: '/catalog', label: '可订阅规则包', icon: Shop },
  { to: '/conches', label: '规则包与账号', icon: Box, badge: pendingConches.value },
  { to: '/identities', label: '身份', icon: User },
  { to: '/apikeys', label: 'API Key', icon: Key },
  { to: '/calls', label: '调用记录', icon: List },
  { to: '/audit', label: '配置审计', icon: Document },
  { to: '/files', label: '文件暂存区', icon: FolderOpened },
  { to: '/recorder', label: '浏览器录制', icon: VideoCamera },
  { to: '/reports', label: '上报', icon: DataAnalysis },
  { to: '/reef', label: '连接 Reef', icon: Connection },
  { to: '/trust', label: 'HTTPS 信任', icon: Unlock },
])
const passwordOpen = ref(false)

onMounted(refreshPending)
</script>

<template>
  <AppLayout product="Crab" mark="crab" subtitle="Hermit 本地控制台" :items="items">
    <template #topbar>
      <el-tag type="info" effect="plain" size="small">仅限本机访问</el-tag>
      <el-button
        v-if="consoleSession.required"
        text
        size="small"
        :icon="Lock"
        data-test="change-password"
        @click="passwordOpen = true"
      >
        修改口令
      </el-button>
    </template>
    <template #sidebar-foot>
      <span>凭证只保存在本机，加密存储</span>
    </template>
  </AppLayout>
  <PasswordDialog v-model="passwordOpen" />
</template>
