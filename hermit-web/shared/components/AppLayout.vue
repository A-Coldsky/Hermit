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

const COLLAPSE_KEY = 'hermit.sidebar.collapsed'
const DRAWER_QUERY = '(max-width: 900px)'

function initialCollapsed(): boolean {
  try {
    const stored = localStorage.getItem(COLLAPSE_KEY)
    if (stored !== null) {
      return stored === '1'
    }
  } catch {
    // 读不到就按屏幕宽度决定
  }
  return window.innerWidth < 1180
}

const route = useRoute()
const collapsed = ref(initialCollapsed())
/** 窄屏下侧栏是抽屉，和桌面端的折叠状态分开记。 */
const drawerOpen = ref(false)

watch(collapsed, (value) => {
  try {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
  } catch {
    // 写不进去只影响下次打开
  }
})
watch(() => route.fullPath, () => (drawerOpen.value = false))

const activeItem = computed(() =>
  [...props.items]
    .sort((a, b) => b.to.length - a.to.length)
    .find((item) => route.path === item.to || route.path.startsWith(item.to === '/' ? '/__none__' : `${item.to}/`)),
)
const active = computed(() => activeItem.value?.to ?? '/')

function toggleSidebar() {
  if (typeof matchMedia === 'function' && matchMedia(DRAWER_QUERY).matches) {
    drawerOpen.value = !drawerOpen.value
    return
  }
  collapsed.value = !collapsed.value
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
        <el-tooltip
          v-for="item in items"
          :key="item.to"
          :content="item.label"
          placement="right"
          :disabled="!collapsed || drawerOpen"
          :show-after="200"
        >
          <RouterLink :to="item.to" class="nav-item" :class="{ active: active === item.to }">
            <el-icon class="icon"><component :is="item.icon" /></el-icon>
            <span class="label">{{ item.label }}</span>
            <span v-if="item.badge" class="badge">{{ item.badge > 99 ? '99+' : item.badge }}</span>
          </RouterLink>
        </el-tooltip>
      </nav>
      <div class="sidebar-foot"><slot name="sidebar-foot" /></div>
    </aside>
    <div class="scrim" @click="drawerOpen = false" />
    <div class="main">
      <header class="topbar">
        <button
          type="button"
          class="icon-btn"
          :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
          :title="collapsed ? '展开侧栏' : '收起侧栏'"
          @click="toggleSidebar"
        >
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
          <Transition name="hm-page" mode="out-in">
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
  background:
    radial-gradient(120% 60% at 0% 0%, rgba(20, 184, 166, 0.12), transparent 60%),
    var(--hm-sidebar);
  color: var(--hm-sidebar-text);
  display: flex;
  flex-direction: column;
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 30;
  transition: width 0.2s var(--hm-ease);
  overflow: hidden;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  margin-left: var(--sidebar-width);
  transition: margin-left 0.2s var(--hm-ease);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 17px 16px;
  white-space: nowrap;
}

.logo {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  background: #e6fffb;
  border-radius: 10px;
  padding: 3px;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08), 0 6px 16px -6px rgba(20, 184, 166, 0.6);
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
  padding: 8px 10px;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 38px;
  padding: 0 13px;
  border-radius: 9px;
  color: var(--hm-sidebar-text);
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.nav-item .icon {
  font-size: 17px;
  flex-shrink: 0;
}

.nav-item:hover {
  background: var(--hm-sidebar-hover);
  color: #fff;
}

.nav-item.active {
  background: linear-gradient(90deg, rgba(20, 184, 166, 0.22), rgba(20, 184, 166, 0.06));
  color: #fff;
}

.nav-item.active .icon {
  color: #5eead4;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #2dd4bf;
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

.collapsed .brand-text,
.collapsed .label,
.collapsed .sidebar-foot {
  display: none;
}

.collapsed .badge {
  position: absolute;
  top: 5px;
  right: 6px;
  min-width: 8px;
  height: 8px;
  padding: 0;
  font-size: 0;
  box-shadow: 0 0 0 2px var(--hm-sidebar);
}

.sidebar-foot {
  padding: 14px 18px;
  font-size: 12px;
  line-height: 1.5;
  opacity: 0.8;
  border-top: 1px solid var(--hm-sidebar-line);
}

.sidebar-foot:empty {
  display: none;
}

.scrim {
  display: none;
}

.topbar {
  height: 56px;
  background: var(--hm-topbar);
  backdrop-filter: saturate(1.6) blur(10px);
  border-bottom: 1px solid var(--hm-border);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px 0 14px;
  position: sticky;
  top: 0;
  z-index: 20;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  background: transparent;
  color: var(--hm-muted);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.icon-btn:hover {
  background: var(--el-fill-color-light);
  color: var(--hm-text);
}

.section {
  font-weight: 600;
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.content {
  padding: 24px 28px 40px;
  max-width: 1480px;
  width: 100%;
}

@media (max-width: 900px) {
  .layout,
  .layout.collapsed {
    --sidebar-width: 240px;
  }

  .sidebar {
    transform: translateX(-100%);
    transition: transform 0.22s var(--hm-ease);
    box-shadow: var(--hm-shadow-raised);
  }

  .main {
    margin-left: 0;
  }

  .collapsed .brand-text,
  .collapsed .label {
    display: block;
  }

  .collapsed .sidebar-foot {
    display: block;
  }

  .collapsed .badge {
    position: static;
    min-width: 0;
    height: auto;
    padding: 1px 7px;
    font-size: 11px;
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
