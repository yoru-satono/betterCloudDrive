<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import { useUIStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useSearchContextStore } from '@/stores/searchContext'
import { useRoute, useRouter } from 'vue-router'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getSearchTriggerPlaceholder, resolveSearchMode } from '@/config/search'
import { Bell } from 'lucide-vue-next'
import * as notificationsApi from '@/api/notifications'

const ui = useUIStore()
const auth = useAuthStore()
const searchContext = useSearchContextStore()
const router = useRouter()
const route = useRoute()
const searchMode = computed(() => resolveSearchMode(route))
const searchPlaceholder = computed(() => getSearchTriggerPlaceholder(searchMode.value, searchContext.activeTag))
const unreadCount = ref(0)
let notificationStream: EventSource | null = null

async function loadUnreadCount() {
  try {
    const { data } = await notificationsApi.getUnreadCount()
    unreadCount.value = Number(data.data || 0)
  } catch {
    unreadCount.value = 0
  }
}

async function handleLogout() {
  closeNotificationStream()
  await auth.logout()
  router.push('/login')
}

function handleRealtimeNotification(event: Event) {
  const message = event as MessageEvent
  try {
    const payload = JSON.parse(message.data || '{}') as { unreadCount?: number }
    if (typeof payload.unreadCount === 'number') unreadCount.value = payload.unreadCount
    window.dispatchEvent(new CustomEvent('bcd:notifications-changed', { detail: payload }))
  } catch {
    window.dispatchEvent(new Event('bcd:notifications-changed'))
  }
}

async function openNotificationStream() {
  closeNotificationStream()
  try {
    notificationStream = await notificationsApi.createNotificationEventSource()
  } catch {
    notificationStream = null
  }
  if (!notificationStream) return
  notificationStream.addEventListener('connected', handleRealtimeNotification)
  notificationStream.addEventListener('notifications-changed', handleRealtimeNotification)
}

function closeNotificationStream() {
  if (!notificationStream) return
  notificationStream.removeEventListener('connected', handleRealtimeNotification)
  notificationStream.removeEventListener('notifications-changed', handleRealtimeNotification)
  notificationStream.close()
  notificationStream = null
}

onMounted(() => {
  loadUnreadCount()
  openNotificationStream()
  window.addEventListener('bcd:notifications-changed', loadUnreadCount)
})

onUnmounted(() => {
  closeNotificationStream()
  window.removeEventListener('bcd:notifications-changed', loadUnreadCount)
})

watch(() => route.fullPath, () => {
  loadUnreadCount()
})
</script>

<template>
  <header class="app-header">
    <div class="app-header__search" @click="ui.openSearch">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>
      <span>{{ searchPlaceholder }}</span>
      <kbd>⌘K</kbd>
    </div>
    <div class="app-header__right">
      <OButton class="app-header__notification" variant="subtle" size="sm" :icon="true" @click="router.push('/notifications')">
        <Bell :size="14" />
        <span v-if="unreadCount > 0" class="app-header__badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
      </OButton>
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
.app-header__notification {
  position: relative;
}
.app-header__badge {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--danger);
  color: white;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  font-weight: 600;
}
</style>
