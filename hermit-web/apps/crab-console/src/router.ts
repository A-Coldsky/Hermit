import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'

export const router = createRouter({
  history: createWebHistory('/console/'),
  routes: [
    {
      path: '/',
      component: App,
      children: [
        { path: '', name: 'overview', component: () => import('./views/OverviewView.vue') },
        { path: 'conches', name: 'conches', component: () => import('./views/ConchesView.vue') },
        { path: 'conches/:conch', name: 'conch', component: () => import('./views/ConchDetailView.vue'), props: true },
        { path: 'apikeys', name: 'apikeys', component: () => import('./views/ApiKeysView.vue') },
        { path: 'calls', name: 'calls', component: () => import('./views/CallsView.vue') },
        { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue') },
        { path: 'reports', name: 'reports', component: () => import('./views/ReportsView.vue') },
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
