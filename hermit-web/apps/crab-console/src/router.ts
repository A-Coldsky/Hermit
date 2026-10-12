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
        { path: 'shells', name: 'shells', component: () => import('./views/ShellsView.vue') },
        { path: 'shells/:shell', name: 'shell', component: () => import('./views/ShellDetailView.vue'), props: true },
        { path: 'apikeys', name: 'apikeys', component: () => import('./views/ApiKeysView.vue') },
        { path: 'calls', name: 'calls', component: () => import('./views/CallsView.vue') },
        { path: 'reports', name: 'reports', component: () => import('./views/ReportsView.vue') },
        { path: ':pathMatch(.*)*', redirect: '/' },
      ],
    },
  ],
})
