import { createRouter, createWebHistory } from 'vue-router'
import { signedIn } from './auth'

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
        { path: 'conches/upload', name: 'upload', component: () => import('./views/UploadView.vue') },
        { path: 'conches/:conch', name: 'conch', component: () => import('./views/ConchDetailView.vue'), props: true },
        { path: 'tide', name: 'tide', component: () => import('./views/TideView.vue') },
        { path: 'alerts', name: 'alerts', component: () => import('./views/AlertsView.vue') },
        { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue') },
        { path: 'settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
        { path: 'shells', redirect: '/conches' },
        { path: 'shells/:conch', redirect: (to) => `/conches/${encodeURIComponent(String(to.params.conch))}` },
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: () => import('@shared/components/NotFoundView.vue'),
          props: { home: '/', homeLabel: '返回概览' },
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (!to.meta.public && !signedIn()) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.name === 'login' && signedIn()) {
    return { name: 'dashboard' }
  }
  return true
})
