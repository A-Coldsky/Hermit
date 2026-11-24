<script setup lang="ts">
import {
  ArrowDown,
  Bell,
  Box,
  Checked,
  DataLine,
  Document,
  Download,
  House,
  Key,
  Reading,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import PasswordDialog from './components/PasswordDialog.vue'
import { useRouter } from 'vue-router'
import AppLayout, { type NavItem } from '@shared/components/AppLayout.vue'
import { api } from './api'
import { isAdmin, session, setRole, signedIn, signOut } from './auth'
import { openAlerts, refreshOpenAlerts } from './alerts'
import { pendingSubmissions, refreshPendingSubmissions } from './submissions'

const router = useRouter()
const timer = ref<number>()
let mounted = true

const passwordOpen = ref(false)

const items = computed<NavItem[]>(() => [
  { to: '/', label: '概览', icon: House },
  { to: '/conches', label: '规则包目录', icon: Box },
  {
    to: '/submissions',
    label: isAdmin.value ? '规则审批' : '规则提交',
    icon: Checked,
    badge: pendingSubmissions.value,
  },
  { to: '/tide', label: 'Tide 统计', icon: DataLine },
  { to: '/alerts', label: '告警', icon: Bell, badge: openAlerts.value },
  { to: '/tokens', label: '我的令牌', icon: Tickets },
  { to: '/crab', label: '下载 Crab', icon: Download },
  { to: '/guide', label: '使用教程', icon: Reading },
  ...(isAdmin.value
    ? [
        { to: '/users', label: '用户管理', icon: User },
        { to: '/audit', label: '审计日志', icon: Document },
      ]
    : []),
  { to: '/settings', label: '签名公钥', icon: Key },
])

const roleLabel = computed(() => (session.role === 'ADMIN' ? '管理员' : session.role === 'USER' ? '普通用户' : ''))

function onCommand(command: string) {
  if (command === 'password') passwordOpen.value = true
  else if (command === 'logout') logout()
}

function refreshBadges() {
  void refreshOpenAlerts()
  void refreshPendingSubmissions()
}

onMounted(async () => {
  try {
    const me = await api.me()
    session.user = me.username
    setRole(me.role)
    if (router.currentRoute.value.meta.admin && me.role !== 'ADMIN') {
      ElMessage.warning({ message: '没有权限执行这个操作', grouping: true })
      void router.replace({ name: 'dashboard' })
    }
  } catch {
    // 401 已在客户端里处理
    if (!signedIn()) {
      return
    }
  }
  if (!mounted) {
    return
  }
  refreshBadges()
  timer.value = window.setInterval(() => refreshBadges(), 60_000)
})

onBeforeUnmount(() => {
  mounted = false
  window.clearInterval(timer.value)
})

function logout() {
  signOut()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <AppLayout product="Reef" mark="reef" subtitle="Hermit 管理端" :items="items">
    <template #topbar>
      <el-dropdown trigger="click" @command="onCommand">
        <el-button text data-test="account-menu">
          {{ session.user }}
          <el-tag v-if="roleLabel" size="small" effect="plain" :type="isAdmin ? 'primary' : 'info'" class="role">{{ roleLabel }}</el-tag>
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="password">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <PasswordDialog v-model="passwordOpen" />
    </template>
    <template #sidebar-foot>
      <span>发布签名规则包 · 回收运行指标</span>
    </template>
  </AppLayout>
</template>

<style scoped>
.role {
  margin-left: 6px;
}
</style>
