<script setup lang="ts">
import { Expand, Fold, Moon, Sunny } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { colorMode, toggleColorMode } from '../color-mode'

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

const COLLAPSED_KEY = 'hermit.sidebar-collapsed'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

const route = useRoute()
const collapsed = ref(readCollapsed())
/** 窄屏下侧栏是抽屉，和桌面端的折叠状态分开记。 */
const drawerOpen = ref(false)

const activeItem = computed(() =>
  [...props.items]
    .sort((a, b) => b.to.length - a.to.length)
    .find((item) => route.path === item.to || route.path.startsWith(item.to === '/' ? '/__none__' : `${item.to}/`)),
)
const active = computed(() => activeItem.value?.to ?? '/')

watch(() => route.fullPath, () => (drawerOpen.value = false))

function toggleSidebar() {
  if (window.matchMedia('(max-width: 900px)').matches) {
    drawerOpen.value = !drawerOpen.value
    return
  }
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem(COLLAPSED_KEY, collapsed.value ? '1' : '0')
  } catch {
    // 写不进去只影响下次打开
  }
}
</script>

<template>
  <div class="layout" :class="{ collapsed, 'drawer-open': drawerOpen }">
    <aside class="sidebar">
      <div class="brand">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M6 22c0-7 5-13 12-13 4 0 8 3 8 8v5H6z" fill="#14b8a6" />
          <path d="M10 22c0-4 3-8 8-8" stroke="#10272b" stroke-width="2" fill="none" />
          <circle cx="22" cy="12" r="1.6" fill="#10272b" />
          <path d="M4 24h24" stroke="#f59e0b" stroke-width="2.4" stroke-linecap="round" />
        </svg>
        <div class="brand-text">
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
          :title="collapsed ? item.label : undefined"
        >
          <el-icon :size="17"><component :is="item.icon" /></el-icon>
          <span class="label">{{ item.label }}</span>
          <span v-if="item.badge" class="badge">{{ item.badge > 99 ? '99+' : item.badge }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot"><slot name="sidebar-foot" /></div>
    </aside>
    <div class="scrim" @click="drawerOpen = false" />
    <div class="main">
      <header class="topbar">
        <button type="button" class="icon-btn" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="toggleSidebar">
          <el-icon :size="18"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </button>
        <span class="section">{{ activeItem?.label }}</span>
        <span class="spacer" />
        <slot name="topbar" />
        <button
          type="button"
          class="icon-btn"
          :aria-label="colorMode === 'dark' ? '切换到浅色' : '切换到深色'"
          :title="colorMode === 'dark' ? '切换到浅色' : '切换到深色'"
          @click="toggleColorMode"
        >
          <el-icon :size="17"><Sunny v-if="colorMode === 'dark'" /><Moon v-else /></el-icon>
        </button>
      </header>
      <main class="content">
        <RouterView v-slot="{ Component: view, route: current }">
          <Transition name="page" mode="out-in">
            <component :is="view" :key="current.path" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  --sidebar-width: 232px;
  display: flex;
  min-height: 100%;
}

.layout.collapsed {
  --sidebar-width: 68px;
}

.sidebar {
  width: var(--sidebar-width);
  flex-shrink: 0;
  background: var(--hm-sidebar);
  color: var(--hm-sidebar-text);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
  transition: width 0.2s var(--hm-ease);
  z-index: 30;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 17px;
  height: 72px;
}

.logo {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  background: #e6fffb;
  border-radius: 9px;
  padding: 3px;
}

.brand-text,
.label,
.badge,
.sidebar-foot {
  transition: opacity 0.15s;
  white-space: nowrap;
}

.product {
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0.2px;
}

.subtitle {
  font-size: 12px;
  opacity: 0.7;
}

nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 12px;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 12px;
  border-radius: 8px;
  color: var(--hm-sidebar-text);
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: var(--hm-sidebar-hover);
  color: #fff;
}

.nav-item.active {
  background: var(--hm-sidebar-active);
  color: #fff;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--hm-brand);
}

.nav-item.active .el-icon {
  color: #5eead4;
}

.label {
  flex: 1;
}

.badge {
  background: var(--hm-accent);
  color: #1f1300;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  min-width: 18px;
  text-align: center;
  border-radius: 999px;
  padding: 0 6px;
}

.sidebar-foot {
  padding: 14px 18px;
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
  border-top: 1px solid var(--hm-sidebar-line);
  opacity: 0.8;
}

.sidebar-foot:empty {
  display: none;
}

.collapsed .brand-text,
.collapsed .label,
.collapsed .sidebar-foot {
  opacity: 0;
  pointer-events: none;
}

/* 折叠后角标缩成图标右上角的小圆点。 */
.collapsed .badge {
  position: absolute;
  top: 6px;
  left: 26px;
  min-width: 8px;
  width: 8px;
  height: 8px;
  padding: 0;
  font-size: 0;
  line-height: 0;
  box-shadow: 0 0 0 2px var(--hm-sidebar);
}

.scrim {
  display: none;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  background: var(--hm-topbar);
  backdrop-filter: saturate(180%) blur(10px);
  border-bottom: 1px solid var(--hm-border);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px 0 12px;
  position: sticky;
  top: 0;
  z-index: 20;
}

.section {
  font-weight: 600;
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--hm-muted);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.icon-btn:hover {
  background: var(--hm-surface-2);
  color: var(--hm-text);
}

.content {
  padding: 24px;
  max-width: 1440px;
  width: 100%;
}

.page-enter-active,
.page-leave-active {
  transition: opacity 0.14s var(--hm-ease), transform 0.14s var(--hm-ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.page-leave-to {
  opacity: 0;
}

@media (max-width: 900px) {
  .layout,
  .layout.collapsed {
    --sidebar-width: 240px;
  }

  .sidebar {
    position: fixed;
    left: 0;
    transform: translateX(-100%);
    transition: transform 0.22s var(--hm-ease);
    box-shadow: var(--hm-shadow-raised);
  }

  .collapsed .brand-text,
  .collapsed .label,
  .collapsed .sidebar-foot {
    opacity: 1;
    pointer-events: auto;
  }

  .collapsed .badge {
    position: static;
    width: auto;
    height: auto;
    min-width: 18px;
    padding: 0 6px;
    font-size: 11px;
    line-height: 18px;
    box-shadow: none;
  }

  .drawer-open .sidebar {
    transform: none;
  }

  .drawer-open .scrim {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.35);
    z-index: 25;
  }

  .content {
    padding: 16px;
  }
}
</style>
