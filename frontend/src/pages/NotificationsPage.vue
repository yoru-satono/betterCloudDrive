<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as notificationsApi from '@/api/notifications'
import type { NotificationEntity, NotificationTypeFilter } from '@/api/notifications'
import { toast } from 'vue-sonner'

const router = useRouter()
const { formatDateFull } = useFormatters()

const notifications = ref<NotificationEntity[]>([])
const loading = ref(false)
const unreadOnly = ref(false)
const typeFilter = ref<NotificationTypeFilter | ''>('')
const page = ref(1)
const total = ref(0)
const pageSize = 20
const typeOptions: Array<{ label: string; value: NotificationTypeFilter | '' }> = [
  { label: '全部类型', value: '' },
  { label: '团队邀请', value: 'TEAM_INVITE' },
  { label: '邀请接受', value: 'TEAM_INVITE_ACCEPT' },
  { label: '邀请拒绝', value: 'TEAM_INVITE_REJECT' },
  { label: '配额告警', value: 'TEAM_QUOTA_ALERT' },
  { label: '团队归档', value: 'TEAM_ARCHIVE' },
  { label: '取消归档', value: 'TEAM_UNARCHIVE' },
  { label: '团队恢复', value: 'TEAM_RESTORE' },
  { label: '团队删除', value: 'TEAM_DELETE' },
  { label: '权限变更', value: 'TEAM_PERMISSION_UPDATE' },
  { label: '成员加入', value: 'TEAM_MEMBER_ADD' },
  { label: '角色变更', value: 'TEAM_ROLE_UPDATE' },
  { label: '成员移除', value: 'TEAM_MEMBER_REMOVE' },
  { label: '所有权转让', value: 'TEAM_OWNER_TRANSFER' },
]
const emptyTitle = computed(() => {
  if (unreadOnly.value && typeFilter.value) return '暂无匹配的未读通知'
  if (unreadOnly.value) return '暂无未读通知'
  if (typeFilter.value) return '暂无匹配类型的通知'
  return '暂无通知'
})

async function load() {
  loading.value = true
  try {
    const { data } = await notificationsApi.listNotifications(
      page.value,
      pageSize,
      unreadOnly.value,
      typeFilter.value || undefined,
    )
    notifications.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

async function openNotification(notification: NotificationEntity) {
  if (!notification.isRead) {
    await notificationsApi.markNotificationRead(notification.id)
    notification.isRead = true
    window.dispatchEvent(new Event('bcd:notifications-changed'))
  }
  if (notification.link) {
    await router.push(notification.link)
  }
}

async function markAllRead() {
  await notificationsApi.markAllNotificationsRead()
  toast.success('通知已全部标记为已读')
  window.dispatchEvent(new Event('bcd:notifications-changed'))
  await load()
}

function toggleUnreadOnly() {
  unreadOnly.value = !unreadOnly.value
  page.value = 1
  load()
}

function changeTypeFilter() {
  page.value = 1
  load()
}

function handleNotificationsChanged() {
  load()
}

onMounted(() => {
  load()
  window.addEventListener('bcd:notifications-changed', handleNotificationsChanged)
})

onUnmounted(() => {
  window.removeEventListener('bcd:notifications-changed', handleNotificationsChanged)
})
</script>

<template>
  <div class="notifications-page page-enter">
    <div class="notifications-page__header">
      <div>
        <h2>通知</h2>
        <p>团队邀请、配额告警和团队状态变更会出现在这里</p>
      </div>
      <div class="notifications-page__actions">
        <select v-model="typeFilter" class="notifications-page__type" @change="changeTypeFilter">
          <option v-for="option in typeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
        <OButton variant="ghost" size="sm" @click="toggleUnreadOnly">{{ unreadOnly ? '全部通知' : '仅未读' }}</OButton>
        <OButton variant="ghost" size="sm" @click="markAllRead">全部已读</OButton>
      </div>
    </div>

    <div v-if="loading" class="notifications-page__loading"><OSpinner /></div>
    <OEmptyState v-else-if="notifications.length === 0" :title="emptyTitle" />
    <div v-else class="notifications-list">
      <button
        v-for="notification in notifications"
        :key="notification.id"
        class="notification-row"
        :class="{ 'notification-row--unread': !notification.isRead }"
        type="button"
        @click="openNotification(notification)"
      >
        <span class="notification-row__dot" />
        <span class="notification-row__main">
          <span class="notification-row__title">{{ notification.title }}</span>
          <span class="notification-row__content">{{ notification.content }}</span>
        </span>
        <span class="notification-row__meta">
          <span class="notification-row__type">{{ notification.type }}</span>
          <span>{{ formatDateFull(notification.createdAt) }}</span>
        </span>
      </button>
    </div>

    <div v-if="total > pageSize" class="notifications-page__pagination">
      <OButton variant="ghost" size="sm" :disabled="page <= 1" @click="page--; load()">上一页</OButton>
      <span class="font-mono">第 {{ page }} 页 · 共 {{ total }} 条</span>
      <OButton variant="ghost" size="sm" :disabled="page * pageSize >= total" @click="page++; load()">下一页</OButton>
    </div>
  </div>
</template>

<style scoped>
.notifications-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.notifications-page__header h2 {
  margin: 0 0 6px;
  font-size: 22px;
}
.notifications-page__header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.notifications-page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notifications-page__type {
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
}
.notifications-page__loading {
  display: flex;
  justify-content: center;
  padding: 60px;
}
.notifications-list {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.notification-row {
  width: 100%;
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) 220px;
  gap: 12px;
  align-items: center;
  text-align: left;
  border: 0;
  border-bottom: 1px solid var(--border);
  background: transparent;
  color: var(--text-primary);
  padding: 14px 16px;
  cursor: pointer;
}
.notification-row:last-child {
  border-bottom: 0;
}
.notification-row:hover {
  background: var(--bg-elevated);
}
.notification-row__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: transparent;
}
.notification-row--unread .notification-row__dot {
  background: var(--accent);
}
.notification-row__main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.notification-row__title {
  font-weight: 600;
  font-size: 14px;
}
.notification-row__content {
  color: var(--text-secondary);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-row__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-end;
  color: var(--text-muted);
  font-size: 12px;
}
.notification-row__type {
  color: var(--accent);
  font-family: var(--font-mono);
}
.notifications-page__pagination {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}
@media (max-width: 720px) {
  .notification-row {
    grid-template-columns: 10px minmax(0, 1fr);
  }
  .notification-row__meta {
    grid-column: 2;
    align-items: flex-start;
  }
}
</style>
