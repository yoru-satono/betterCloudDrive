<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { onMounted } from 'vue'
import { i18n } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const t = i18n.global.t
onMounted(() => { if (!auth.isAdmin) router.push('/files') })
</script>

<template>
  <div class="admin-layout">
    <div class="admin-layout__header">
      <span class="admin-layout__badge">ADMIN</span>
      <span class="admin-layout__title">{{ t('admin.title') }}</span>
      <RouterLink to="/files" style="color:var(--text-secondary);font-size:13px;text-decoration:none">← {{ t('admin.back') }}</RouterLink>
    </div>
    <nav class="admin-layout__nav">
      <RouterLink to="/admin" exact-active-class="admin-nav--active" class="admin-nav-link">{{ t('admin.overview') }}</RouterLink>
      <RouterLink to="/admin/users" active-class="admin-nav--active" class="admin-nav-link">{{ t('admin.users') }}</RouterLink>
      <RouterLink to="/admin/teams" active-class="admin-nav--active" class="admin-nav-link">{{ t('admin.teams') }}</RouterLink>
      <RouterLink to="/admin/logs" active-class="admin-nav--active" class="admin-nav-link">{{ t('admin.logs') }}</RouterLink>
      <RouterLink to="/admin/dead-letters" active-class="admin-nav--active" class="admin-nav-link">{{ t('admin.deadLetters') }}</RouterLink>
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
@media (max-width: 719px) {
  .admin-layout__header { height: auto; min-height: 52px; padding: 10px 16px; flex-wrap: wrap; }
  .admin-layout__nav { padding: 8px 12px 0; overflow-x: auto; }
  .admin-nav-link { white-space: nowrap; padding-inline: 11px; }
  .admin-layout__content { padding: 16px; overflow-x: hidden; }
}
</style>
