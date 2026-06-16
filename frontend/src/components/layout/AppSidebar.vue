<script setup lang="ts">
import { useUIStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import { useRouter, useRoute } from 'vue-router'
import { isDesktopRuntime } from '@/config/runtime'

const ui = useUIStore()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { formatSize } = useFormatters()

const navItems = [
  { to: '/files',       label: '全部文件',  icon: 'folder' },
  { to: '/favorites',   label: '我的收藏',  icon: 'star' },
  { to: '/shares',      label: '我的分享',  icon: 'share' },
  { to: '/tags',        label: '标签管理',  icon: 'tag' },
  { to: '/recycle-bin', label: '回收站',    icon: 'trash' },
]

const desktopItems = [
  { to: '/settings',    label: '设置',      icon: 'settings' },
]

const adminItems = [
  { to: '/admin',       label: '管理后台',  icon: 'shield' },
]

const iconPaths: Record<string, string> = {
  folder: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z',
  star:   'M12 2l3.1 6.3 6.9 1-5 4.9 1.2 6.9L12 18l-6.2 3.1 1.2-6.9L2 9.3l6.9-1z',
  share:  'M4 12v8a2 2 0 002 2h12a2 2 0 002-2v-8M16 6l-4-4-4 4M12 2v13',
  tag:    'M20.6 8.3l-13 13a2 2 0 01-2.8 0L3 19.5a2 2 0 010-2.8l13-13A2 2 0 0118 3l2.6 2.6a2 2 0 010 2.7z',
  trash:  'M3 6h18M8 6V4h8v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6',
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
  settings:'M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a7.6 7.6 0 00.15-1.5 7.6 7.6 0 00-.15-1.5l2.3-1.8a.5.5 0 00.1-.6l-2.2-3.8a.5.5 0 00-.6-.2l-2.7 1.1a7.9 7.9 0 00-2.6-1.5l-.4-2.8A.5.5 0 0012 2h-4.4a.5.5 0 00-.5.4l-.4 2.8a7.9 7.9 0 00-2.6 1.5L1.4 5.6a.5.5 0 00-.6.2L.6 9.6a.5.5 0 00.1.6l2.3 1.8a7.6 7.6 0 000 3l-2.3 1.8a.5.5 0 00-.1.6l2.2 3.8a.5.5 0 00.6.2l2.7-1.1a7.9 7.9 0 002.6 1.5l.4 2.8a.5.5 0 00.5.4H12a.5.5 0 00.5-.4l.4-2.8a7.9 7.9 0 002.6-1.5l2.7 1.1a.5.5 0 00.6-.2l2.2-3.8a.5.5 0 00-.1-.6z',
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--expanded': ui.sidebarExpanded }">
    <!-- Logo -->
    <div class="sidebar__logo" @click="ui.toggleSidebar">
      <div class="sidebar__logo-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2">
          <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
          <polyline points="9 22 9 12 15 12 15 22"/>
        </svg>
      </div>
      <Transition name="fade">
        <span v-if="ui.sidebarExpanded" class="sidebar__brand">BetterCloudDrive</span>
      </Transition>
    </div>

    <!-- Navigation -->
    <nav class="sidebar__nav">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="sidebar__item"
        :class="{ 'sidebar__item--active': route.path.startsWith(item.to) }"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path :d="iconPaths[item.icon]" />
        </svg>
        <Transition name="fade">
          <span v-if="ui.sidebarExpanded" class="sidebar__label">{{ item.label }}</span>
        </Transition>
      </RouterLink>

      <template v-if="isDesktopRuntime()">
        <div class="sidebar__divider" />
        <RouterLink
          v-for="item in desktopItems"
          :key="item.to"
          :to="item.to"
          class="sidebar__item"
          :class="{ 'sidebar__item--active': route.path.startsWith(item.to) }"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path :d="iconPaths[item.icon]" />
          </svg>
          <Transition name="fade">
            <span v-if="ui.sidebarExpanded" class="sidebar__label">{{ item.label }}</span>
          </Transition>
        </RouterLink>
      </template>

      <div v-if="auth.isAdmin" class="sidebar__divider" />

      <RouterLink
        v-for="item in auth.isAdmin ? adminItems : []"
        :key="item.to"
        :to="item.to"
        class="sidebar__item"
        :class="{ 'sidebar__item--active': route.path.startsWith(item.to) }"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path :d="iconPaths[item.icon]" />
        </svg>
        <Transition name="fade">
          <span v-if="ui.sidebarExpanded" class="sidebar__label">{{ item.label }}</span>
        </Transition>
      </RouterLink>
    </nav>

    <!-- Storage quota -->
    <div v-if="ui.sidebarExpanded && auth.user" class="sidebar__quota">
      <div class="sidebar__quota-bar">
        <div class="sidebar__quota-fill" :style="{ width: `${auth.storagePercent}%` }" />
      </div>
      <div class="sidebar__quota-text">
        <span class="font-mono">{{ formatSize(auth.user.storageUsed) }}</span>
        <span class="text-muted"> / {{ formatSize(auth.user.storageQuota) }}</span>
      </div>
    </div>

    <!-- User -->
    <RouterLink to="/profile" class="sidebar__user">
      <div class="sidebar__avatar">{{ auth.user?.username?.[0]?.toUpperCase() }}</div>
      <Transition name="fade">
        <div v-if="ui.sidebarExpanded" class="sidebar__user-info">
          <span class="sidebar__user-name truncate">{{ auth.user?.username }}</span>
          <span class="sidebar__user-role">{{ auth.isAdmin ? '管理员' : '普通用户' }}</span>
        </div>
      </Transition>
    </RouterLink>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-collapsed);
  min-height: calc(100vh - var(--desktop-titlebar-h, 0px));
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex; flex-direction: column;
  transition: width var(--base) var(--ease-out);
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar--expanded { width: var(--sidebar-w); }

.sidebar__logo {
  display: flex; align-items: center; gap: 10px;
  padding: 16px; height: var(--topbar-h);
  cursor: pointer; border-bottom: 1px solid var(--border);
  overflow: hidden;
}
.sidebar__logo-icon {
  width: 28px; height: 28px; flex-shrink: 0;
  background: var(--accent-dim); border-radius: 7px;
  display: flex; align-items: center; justify-content: center;
}
.sidebar__brand {
  font-size: 14px; font-weight: 600; color: var(--text-primary);
  letter-spacing: -0.03em; white-space: nowrap;
}

.sidebar__nav { flex: 1; padding: 8px; display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
.sidebar__item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px;
  border-radius: 8px; border: 1px solid transparent;
  color: var(--text-secondary); text-decoration: none;
  transition: all var(--fast);
  overflow: hidden; white-space: nowrap;
  min-height: 36px; flex-shrink: 0;
}
.sidebar__item:hover { background: var(--bg-elevated); color: var(--text-primary); }
.sidebar__item--active {
  background: var(--accent-dim);
  color: var(--accent);
  border-color: rgba(0,212,170,0.15);
}
.sidebar__label { font-size: 13px; }
.sidebar__divider { height: 1px; background: var(--border); margin: 6px 0; }

.sidebar__quota { padding: 12px; border-top: 1px solid var(--border); }
.sidebar__quota-bar {
  height: 3px; background: var(--border); border-radius: 2px; margin-bottom: 6px; overflow: hidden;
}
.sidebar__quota-fill {
  height: 100%; background: var(--accent); border-radius: 2px;
  transition: width 600ms;
}
.sidebar__quota-text { font-size: 11px; color: var(--text-secondary); }

.sidebar__user {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 10px 14px;
  text-decoration: none;
  overflow: hidden;
  transition: background var(--fast);
  border-radius: 0 0 0 0;
}
.sidebar__user:hover { background: var(--bg-elevated); }
.sidebar__avatar {
  width: 28px; height: 28px; flex-shrink: 0;
  background: var(--bg-overlay); border: 1px solid var(--border-hover);
  border-radius: 7px; display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; color: var(--accent);
}
.sidebar__user-info { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.sidebar__user-name { font-size: 13px; color: var(--text-primary); }
.sidebar__user-role { font-size: 11px; color: var(--text-muted); }
</style>
