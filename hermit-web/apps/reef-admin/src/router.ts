import { ElMessage } from 'element-plus'
import { createRouter, createWebHistory } from 'vue-router'
import { api } from './api'
import { markExpiredRedirect, session, sessionPastExpiry, setRole, signedIn } from './auth'

export const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('./App.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('./views/DashboardView.vue') },
        { path: 'conches', name: 'conches', component: () => import('./views/ConchesView.vue') },
        { path: 'conches/upload', name: 'upload', component: () => import('./views/UploadView.vue'), meta: { admin: true } },
        { path: 'conches/:conch', name: 'conch', component: () => import('./views/ConchDetailView.vue'), props: true },
        { path: 'submissions', name: 'submissions', component: () => import('./views/SubmissionsView.vue') },
        { path: 'tide', name: 'tide', component: () => import('./views/TideView.vue') },
        { path: 'alerts', name: 'alerts', component: () => import('./views/AlertsView.vue') },
        { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue'), meta: { admin: true } },
        { path: 'users', name: 'users', component: () => import('./views/UsersView.vue'), meta: { admin: true } },
        { path: 'tokens', name: 'tokens', component: () => import('./views/TokensView.vue') },
        { path: 'crab', name: 'crab', component: () => import('./views/CrabDownloadView.vue') },
        { path: 'guide', name: 'guide', component: () => import('./views/GuideView.vue') },
        { path: 'settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
        { path: 'shells', redirect: '/conches' },
        { path: 'shells/:conch', redirect: (to) => `/conches/${encodeURIComponent(String(to.params.conch))}` },
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: () => import('@shared/components/NotFoundView.vue'),
          props: { home: '/', homeLabel: '返回概览', mark: 'reef' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  // signedIn() 会清掉过期会话，要先记下是过期还是从未登录。
  const expired = sessionPastExpiry()
  if (!to.meta.public && !signedIn()) {
    if (expired) markExpiredRedirect()
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.name === 'login' && signedIn()) {
    return { name: 'dashboard' }
  }
  if (to.meta.admin) {
    if (!session.role) {
      try {
        setRole((await api.me()).role)
      } catch {
        if (!signedIn()) {
          return false
        }
        ElMessage.warning({ message: '无法确认当前权限，请稍后再试', grouping: true })
        return { name: 'dashboard' }
      }
    }
    if (session.role !== 'ADMIN') {
      ElMessage.warning({ message: '没有权限执行这个操作', grouping: true })
      return { name: 'dashboard' }
    }
  }
  return true
})
