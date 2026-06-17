<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { onMounted } from 'vue'

const auth = useAuthStore()
const router = useRouter()
onMounted(() => { if (!auth.isAdmin) router.push('/files') })
</script>

<template>
  <div class="admin-layout">
    <div class="admin-layout__header">
      <span class="admin-layout__badge">ADMIN</span>
      <span class="admin-layout__title">管理后台</span>
      <RouterLink to="/files" style="color:var(--text-secondary);font-size:13px;text-decoration:none">← 返回云盘</RouterLink>
    </div>
    <nav class="admin-layout__nav">
      <RouterLink to="/admin" exact-active-class="admin-nav--active" class="admin-nav-link">概览</RouterLink>
      <RouterLink to="/admin/users" active-class="admin-nav--active" class="admin-nav-link">用户管理</RouterLink>
      <RouterLink to="/admin/logs" active-class="admin-nav--active" class="admin-nav-link">日志中心</RouterLink>
    </nav>
    <main class="admin-layout__content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.admin-layout { min-height: calc(100vh - var(--desktop-titlebar-h, 0px)); background: var(--bg-base); }
.admin-layout__header {
  display: flex; align-items: center; gap: 12px;
  height: 52px; padding: 0 24px;
  background: var(--bg-surface); border-bottom: 1px solid var(--border);
}
.admin-layout__badge {
  font-size: 10px; font-weight: 700; letter-spacing: 0.1em;
  background: var(--accent-dim); color: var(--accent);
  border: 1px solid rgba(0,212,170,0.25); border-radius: 4px; padding: 2px 6px;
}
.admin-layout__title { font-size: 14px; font-weight: 600; flex: 1; }
.admin-layout__nav {
  display: flex; gap: 2px; padding: 12px 24px 0;
  border-bottom: 1px solid var(--border); background: var(--bg-surface);
}
.admin-nav-link {
  padding: 8px 14px; font-size: 13px; text-decoration: none;
  color: var(--text-secondary); border-radius: 7px 7px 0 0;
  border: 1px solid transparent; border-bottom: none;
  transition: all var(--fast);
}
.admin-nav-link:hover { color: var(--text-primary); }
.admin-nav--active {
  color: var(--text-primary) !important;
  background: var(--bg-base);
  border-color: var(--border);
  border-bottom: 1px solid var(--bg-base);
  position: relative; bottom: -1px;
}
.admin-layout__content { padding: 24px; }
</style>
