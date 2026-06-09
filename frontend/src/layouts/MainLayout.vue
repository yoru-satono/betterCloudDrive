<template>
  <div class="main-layout" :class="{ 'sidebar-open': mobileSidebarOpen }">
    <!-- Desktop Sidebar -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <svg width="28" height="28" viewBox="0 0 36 36" fill="none">
          <rect width="36" height="36" rx="10" fill="var(--accent)" opacity="0.15"/>
          <path d="M10 14l8-5 8 5v8l-8 5-8-5v-8z" stroke="var(--accent)" stroke-width="2" fill="none"/>
          <circle cx="18" cy="18" r="3" fill="var(--accent)"/>
        </svg>
        <span v-if="!sidebarCollapsed" class="brand">BetterCloudDrive</span>
      </div>

      <div v-if="!sidebarCollapsed" class="user-card">
        <div class="avatar">{{ auth.user?.username?.charAt(0)?.toUpperCase() }}</div>
        <div class="user-info">
          <div class="user-name truncate">{{ auth.user?.username }}</div>
          <div class="storage">
            <div class="storage-bar">
              <div class="storage-fill" :style="{ width: auth.storagePercent + '%' }"></div>
            </div>
            <span class="storage-text text-mono text-muted">{{ formatSize(auth.user?.storageUsed) }} / {{ formatSize(auth.user?.storageQuota) }}</span>
          </div>
        </div>
      </div>

      <nav class="nav-menu">
        <router-link v-for="item in allNavItems" :key="item.to" :to="item.to" class="nav-item" :class="{ active: isActive(item.to) }">
          <span class="nav-icon" v-html="item.icon"></span>
          <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <router-link to="/profile" class="nav-item">
          <span class="nav-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 4-7 8-7s8 3 8 7"/></svg>
          </span>
          <span v-if="!sidebarCollapsed" class="nav-label">个人设置</span>
        </router-link>
        <button class="nav-item logout-btn" @click="handleLogout">
          <span class="nav-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16,17 21,12 16,7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
          </span>
          <span v-if="!sidebarCollapsed" class="nav-label">退出</span>
        </button>
      </div>
    </aside>

    <!-- Mobile overlay -->
    <div v-if="mobileSidebarOpen" class="sidebar-overlay" @click="mobileSidebarOpen = false"></div>

    <!-- Main content -->
    <div class="main-content">
      <header class="topbar">
        <div class="topbar-left">
          <button class="btn btn-icon btn-ghost menu-toggle" @click="toggleSidebar">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
          </button>
          <div class="breadcrumb" v-if="$route.name !== 'Admin'">
            <template v-for="(crumb, i) in files.breadcrumb" :key="crumb.id">
              <span v-if="i > 0" class="crumb-sep">/</span>
              <router-link v-if="i < files.breadcrumb.length - 1" :to="crumb.id ? `/files/${crumb.id}` : '/files'" class="crumb-link">{{ crumb.name }}</router-link>
              <span v-else class="crumb-current">{{ crumb.name }}</span>
            </template>
          </div>
        </div>
        <div class="topbar-right">
          <div class="search-box">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input v-model="searchQuery" type="text" class="search-input" placeholder="搜索文件..." @input="onSearch" />
          </div>
        </div>
      </header>

      <main class="page-content">
        <router-view />
      </main>
    </div>

    <!-- Mobile bottom nav -->
    <nav class="mobile-nav">
      <router-link v-for="item in mobileNavItems" :key="item.to" :to="item.to" class="mobile-nav-item" :class="{ active: isActive(item.to) }">
        <span v-html="item.icon"></span>
        <span class="mobile-nav-label">{{ item.label }}</span>
      </router-link>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useFilesStore } from '@/stores/files'
import api from '@/api/client'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const files = useFilesStore()

const mobileSidebarOpen = ref(false)
const sidebarCollapsed = ref(false)
const searchQuery = ref('')

const navItems = [
  { to: '/files', label: '我的文件', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>' },
  { to: '/recycle-bin', label: '回收站', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3,6 5,6 21,6"/><path d="M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>' },
  { to: '/shares', label: '分享', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>' },
  { to: '/favorites', label: '收藏', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>' },
  { to: '/tags', label: '标签', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>' },
]

const mobileNavItems = computed(() => {
  if (auth.isAdmin) {
    // 管理后台替换标签页位置（管理后台更常用）
    return [...navItems.slice(0, 4), adminItem]
  }
  return navItems.slice(0, 5)
})

const adminItem = { to: '/admin', label: '管理', icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>' }

const allNavItems = computed(() => auth.isAdmin ? [...navItems, adminItem] : navItems)

function isActive(path: string) { return route.path.startsWith(path) }
function toggleSidebar() {
  if (window.innerWidth < 768) mobileSidebarOpen.value = !mobileSidebarOpen.value
  else sidebarCollapsed.value = !sidebarCollapsed.value
}

let searchTimer: any
function onSearch() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (searchQuery.value.trim()) files.searchFiles(searchQuery.value.trim())
    else files.fetchFiles(files.currentParentId.value)
  }, 300)
}

function formatSize(bytes?: number): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}

async function handleLogout() { await auth.logout(); router.push('/login') }

onMounted(async () => {
  await auth.fetchMe()
  files.fetchFiles(null)
})
</script>

<style scoped>
.main-layout { display: flex; height: 100vh; overflow: hidden; }
.sidebar {
  width: var(--sidebar-width); min-width: var(--sidebar-width);
  background: var(--bg-surface); border-right: 1px solid var(--border-subtle);
  display: flex; flex-direction: column; padding: 16px 12px;
  transition: width var(--duration-normal) var(--ease-out);
  z-index: 50;
}
.sidebar.collapsed { width: var(--sidebar-collapsed); min-width: var(--sidebar-collapsed); }
.sidebar-header { display: flex; align-items: center; gap: 10px; padding: 8px 8px 20px; }
.brand { font-family: var(--font-display); font-size: 16px; font-weight: 700; letter-spacing: -0.02em; }
.user-card { display: flex; align-items: center; gap: 12px; padding: 12px; background: var(--bg-elevated); border-radius: var(--radius-md); margin-bottom: 8px; }
.avatar { width: 36px; height: 36px; border-radius: 50%; background: var(--accent); color: var(--text-inverse); display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 14px; font-family: var(--font-display); flex-shrink: 0; }
.user-info { flex: 1; min-width: 0; }
.user-name { font-weight: 500; font-size: 13px; }
.storage { margin-top: 4px; }
.storage-bar { height: 3px; background: var(--bg-hover); border-radius: 2px; overflow: hidden; margin-bottom: 2px; }
.storage-fill { height: 100%; background: var(--accent); border-radius: 2px; transition: width var(--duration-slow) var(--ease-out); }
.storage-text { font-size: 10px; }

.nav-menu { flex: 1; overflow-y: auto; padding: 8px 0; }
.nav-item {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px;
  border-radius: var(--radius-md); color: var(--text-secondary); cursor: pointer;
  transition: all var(--duration-fast); text-decoration: none; border: none; background: none;
  width: 100%; font-size: 13px; font-family: var(--font-body); margin-bottom: 2px;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-item.active { background: var(--accent-glow); color: var(--accent); }
.nav-icon { display: flex; align-items: center; flex-shrink: 0; opacity: 0.7; }
.nav-item.active .nav-icon { opacity: 1; }
.nav-label { white-space: nowrap; }
.sidebar-footer { border-top: 1px solid var(--border-subtle); padding-top: 8px; }
.logout-btn { color: var(--text-muted); }
.logout-btn:hover { color: var(--red); background: var(--red-glow); }

.main-content { flex: 1; display: flex; flex-direction: column; min-width: 0; overflow: hidden; }
.topbar {
  height: var(--topbar-height); min-height: var(--topbar-height);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; border-bottom: 1px solid var(--border-subtle); gap: 16px;
}
.topbar-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.menu-toggle { flex-shrink: 0; }
.breadcrumb { display: flex; align-items: center; gap: 6px; font-size: 13px; overflow: hidden; }
.crumb-sep { color: var(--text-muted); }
.crumb-link { color: var(--text-secondary); }
.crumb-link:hover { color: var(--accent); }
.crumb-current { color: var(--text-primary); font-weight: 500; white-space: nowrap; }
.topbar-right { display: flex; align-items: center; gap: 12px; }
.search-box { display: flex; align-items: center; gap: 8px; padding: 6px 12px; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-md); width: 240px; }
.search-box:focus-within { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-glow); }
.search-input { background: none; border: none; outline: none; color: var(--text-primary); font-size: 13px; width: 100%; font-family: var(--font-body); }
.search-input::placeholder { color: var(--text-muted); }

.page-content { flex: 1; overflow-y: auto; padding: 24px; }

.mobile-nav { display: none; position: fixed; bottom: 0; left: 0; right: 0; height: var(--mobile-nav-height); background: var(--bg-surface); border-top: 1px solid var(--border-subtle); z-index: 100; }
.mobile-nav-item { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 8px; color: var(--text-muted); text-decoration: none; font-size: 10px; flex: 1; }
.mobile-nav-item.active { color: var(--accent); }
.mobile-nav-label { font-size: 10px; }

.sidebar-overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 40; }

@media (max-width: 1023px) {
  .sidebar { width: var(--sidebar-collapsed); min-width: var(--sidebar-collapsed); }
  .sidebar .brand, .sidebar .user-card, .sidebar .nav-label, .sidebar .sidebar-footer .nav-label { display: none; }
  .sidebar .nav-item { justify-content: center; padding: 10px; }
  .sidebar-header { justify-content: center; padding-bottom: 12px; }
  .search-box { width: 160px; }
}

@media (max-width: 767px) {
  .sidebar { position: fixed; left: -280px; top: 0; bottom: 0; width: 280px; min-width: 280px; border-right: 1px solid var(--border-default); }
  .sidebar-open .sidebar { left: 0; }
  .sidebar-open .sidebar-overlay { display: block; }
  .sidebar .brand, .sidebar .user-card, .sidebar .nav-label, .sidebar .sidebar-footer .nav-label { display: initial; }
  .sidebar .nav-item { justify-content: flex-start; padding: 10px 12px; }
  .mobile-nav { display: flex; }
  .page-content { padding: 16px; padding-bottom: calc(var(--mobile-nav-height) + 16px); }
  .topbar { padding: 0 12px; }
  .search-box { width: 140px; }
  .breadcrumb { display: none; }
}
</style>
