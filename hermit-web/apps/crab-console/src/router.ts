import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { onUnauthorized } from './api'
import { LOGIN_REQUIRED, markSignedOut, sessionGuard } from './session'

export const router = createRouter({
  history: createWebHistory('/console/'),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
    {
      path: '/',
      component: App,
      children: [
        { path: '', name: 'overview', component: () => import('./views/OverviewView.vue') },
        { path: 'conches', name: 'conches', component: () => import('./views/ConchesView.vue') },
        { path: 'catalog', name: 'catalog', component: () => import('./views/CatalogView.vue') },
        { path: 'conches/:conch', name: 'conch', component: () => import('./views/ConchDetailView.vue'), props: true },
        { path: 'apikeys', name: 'apikeys', component: () => import('./views/ApiKeysView.vue') },
        { path: 'calls', name: 'calls', component: () => import('./views/CallsView.vue') },
        { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue') },
        { path: 'reports', name: 'reports', component: () => import('./views/ReportsView.vue') },
        { path: 'identities', name: 'identities', component: () => import('./views/IdentitiesView.vue') },
        { path: 'files', name: 'files', component: () => import('./views/FilesView.vue') },
        { path: 'reef', name: 'reef', component: () => import('./views/ReefView.vue') },
        { path: 'trust', name: 'trust', component: () => import('./views/TrustView.vue') },
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

router.beforeEach(sessionGuard)

onUnauthorized((error) => {
  if (error.message !== LOGIN_REQUIRED) {
    return
  }
  markSignedOut()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})
