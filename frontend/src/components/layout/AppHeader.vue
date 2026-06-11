<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import { useUIStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const ui = useUIStore()
const auth = useAuthStore()
const router = useRouter()

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <header class="app-header">
    <div class="app-header__search" @click="ui.openSearch">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>
      <span>搜索文件...</span>
      <kbd>⌘K</kbd>
    </div>
    <div class="app-header__right">
      <OButton variant="subtle" size="sm" :icon="true" @click="handleLogout">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>
        </svg>
      </OButton>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  height: var(--topbar-h);
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 16px; gap: 12px; flex-shrink: 0;
}
.app-header__search {
  flex: 1; max-width: 400px;
  display: flex; align-items: center; gap: 8px;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 8px; padding: 7px 12px;
  color: var(--text-muted); font-size: 13px;
  cursor: pointer; transition: all var(--fast);
}
.app-header__search:hover { border-color: var(--border-hover); color: var(--text-secondary); }
.app-header__search span { flex: 1; }
.app-header__search kbd {
  font-size: 11px; font-family: var(--font-mono);
  background: var(--bg-overlay); border: 1px solid var(--border-hover);
  border-radius: 4px; padding: 1px 5px;
}
.app-header__right { display: flex; align-items: center; gap: 6px; }
</style>
