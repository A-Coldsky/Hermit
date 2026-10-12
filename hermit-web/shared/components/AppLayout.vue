<script setup lang="ts">
import type { Component } from 'vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

export interface NavItem {
  to: string
  label: string
  icon: Component
  badge?: number
}

const props = defineProps<{
  product: string
  subtitle: string
  items: NavItem[]
}>()

const route = useRoute()
const active = computed(() => {
  const match = [...props.items]
    .sort((a, b) => b.to.length - a.to.length)
    .find((item) => route.path === item.to || route.path.startsWith(item.to === '/' ? '/__none__' : `${item.to}/`))
  return match?.to ?? '/'
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M6 22c0-7 5-13 12-13 4 0 8 3 8 8v5H6z" fill="#14b8a6" />
          <path d="M10 22c0-4 3-8 8-8" stroke="#10272b" stroke-width="2" fill="none" />
          <circle cx="22" cy="12" r="1.6" fill="#10272b" />
          <path d="M4 24h24" stroke="#f59e0b" stroke-width="2.4" stroke-linecap="round" />
        </svg>
        <div>
          <div class="product">{{ product }}</div>
          <div class="subtitle">{{ subtitle }}</div>
        </div>
      </div>
      <nav>
        <RouterLink
          v-for="item in items"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: active === item.to }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span class="label">{{ item.label }}</span>
          <span v-if="item.badge" class="badge">{{ item.badge > 99 ? '99+' : item.badge }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot"><slot name="sidebar-foot" /></div>
    </aside>
    <div class="main">
      <header class="topbar">
        <slot name="topbar" />
      </header>
      <main class="content">
        <RouterView v-slot="{ Component: view }">
          <component :is="view" />
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100%;
}

.sidebar {
  width: 228px;
  flex-shrink: 0;
  background: var(--hm-sidebar);
  color: var(--hm-sidebar-text);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 18px 18px;
}

.logo {
  width: 34px;
  height: 34px;
  background: #e6fffb;
  border-radius: 9px;
  padding: 3px;
}

.product {
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0.2px;
}

.subtitle {
  font-size: 12px;
  opacity: 0.75;
}

nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  flex: 1;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  color: var(--hm-sidebar-text);
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #fff;
}

.nav-item.active {
  background: var(--hm-sidebar-active);
  color: #fff;
  box-shadow: inset 3px 0 0 #14b8a6;
}

.label {
  flex: 1;
}

.badge {
  background: var(--hm-accent);
  color: #1f1300;
  font-size: 11px;
  font-weight: 700;
  border-radius: 999px;
  padding: 1px 7px;
}

.sidebar-foot {
  padding: 14px 18px;
  font-size: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  background: var(--hm-surface);
  border-bottom: 1px solid var(--hm-border);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.content {
  padding: 24px;
  max-width: 1440px;
  width: 100%;
}
</style>
