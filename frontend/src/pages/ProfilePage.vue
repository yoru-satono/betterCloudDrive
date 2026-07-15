<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import * as notificationsApi from '@/api/notifications'
import type { NotificationPreferenceMap, NotificationTypeFilter } from '@/api/notifications'
import { toast } from 'vue-sonner'
import { setThemeMode, themeMode, type ThemeMode } from '@/theme'
import LocaleSwitcher from '@/components/base/LocaleSwitcher.vue'
import { i18n } from '@/i18n'

const auth = useAuthStore()
const t = i18n.global.t
const { formatSize } = useFormatters()

const storagePercent = computed(() => auth.storagePercent)
const storageColor = computed(() => storagePercent.value > 90 ? 'var(--danger)' : storagePercent.value > 70 ? 'var(--warning)' : 'var(--accent)')
const webdavPassword = ref('')
const webdavLoading = ref(false)
const webdavEnabled = computed(() => Boolean(auth.user?.webdavEnabled))
const notificationPreferences = ref<NotificationPreferenceMap>({})
const notificationPreferencesLoading = ref(false)
const notificationPreferenceOptions: NotificationTypeFilter[] = ['TEAM_INVITE', 'TEAM_INVITE_ACCEPT', 'TEAM_INVITE_REJECT', 'TEAM_QUOTA_ALERT', 'TEAM_ARCHIVE', 'TEAM_UNARCHIVE', 'TEAM_RESTORE', 'TEAM_PERMISSION_UPDATE', 'TEAM_DELETE', 'TEAM_MEMBER_ADD', 'TEAM_ROLE_UPDATE', 'TEAM_MEMBER_REMOVE', 'TEAM_OWNER_TRANSFER', 'TEAM_OFFICE_SAVE_FAILED']
const themeOptions = computed<Array<{ value: ThemeMode; label: string }>>(() => [
  { value: 'system', label: t('ui.system') },
  { value: 'light', label: t('ui.light') },
  { value: 'dark', label: t('ui.dark') },
])

async function saveWebDavSettings(enabled: boolean) {
  if (enabled && !webdavPassword.value.trim()) {
    toast.error(t('profile.passwordRequired'))
    return
  }
  webdavLoading.value = true
  try {
    await auth.updateWebDavSettings(enabled, enabled ? webdavPassword.value : undefined)
    webdavPassword.value = ''
    toast.success(enabled ? t('profile.webdavEnabled') : t('profile.webdavDisabled'))
  } finally {
    webdavLoading.value = false
  }
}

function isNotificationEnabled(type: NotificationTypeFilter) {
  return notificationPreferences.value[type] !== false
}

async function loadNotificationPreferences() {
  notificationPreferencesLoading.value = true
  try {
    const { data } = await notificationsApi.getNotificationPreferences()
    notificationPreferences.value = data.data
  } finally {
    notificationPreferencesLoading.value = false
  }
}

async function toggleNotificationPreference(type: NotificationTypeFilter, enabled: boolean) {
  const next = { ...notificationPreferences.value, [type]: enabled }
  notificationPreferences.value = next
  notificationPreferencesLoading.value = true
  try {
    const { data } = await notificationsApi.updateNotificationPreferences({ [type]: enabled })
    notificationPreferences.value = data.data
    toast.success(t('profile.preferencesUpdated'))
  } catch (error) {
    notificationPreferences.value = { ...next, [type]: !enabled }
    throw error
  } finally {
    notificationPreferencesLoading.value = false
  }
}

onMounted(loadNotificationPreferences)
</script>

<template>
  <div class="profile-page page-enter">
    <h2 style="margin-bottom:24px">{{ t('ui.profile') }}</h2>

    <!-- User info card -->
    <div class="profile-card">
      <div class="profile-card__avatar">{{ auth.user?.username?.[0]?.toUpperCase() }}</div>
      <div class="profile-card__info">
        <div class="profile-card__name">{{ auth.user?.username }}</div>
        <div class="profile-card__role">{{ auth.isAdmin ? t('ui.admin') : t('ui.user') }}</div>
        <div class="profile-card__email">{{ auth.user?.email || t('ui.unboundEmail') }}</div>
      </div>
    </div>

    <div class="section">
      <h3 class="section__title">{{ t('ui.appearance') }}</h3>
      <div class="theme-picker" role="group" :aria-label="t('ui.themeMode')">
        <button
          v-for="option in themeOptions"
          :key="option.value"
          type="button"
          class="theme-picker__option"
          :class="{ 'theme-picker__option--active': themeMode === option.value }"
          :aria-pressed="themeMode === option.value"
          :data-testid="`theme-option-${option.value}`"
          @click="setThemeMode(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
      <p class="section__hint theme-picker__hint">{{ t('ui.themeLocal') }}</p>
    </div>

    <div class="section">
      <h3 class="section__title">{{ t('ui.language') }}</h3>
      <LocaleSwitcher />
      <p class="section__hint theme-picker__hint">{{ t('ui.languageSynced') }}</p>
    </div>

    <!-- Storage usage -->
    <div class="section">
      <h3 class="section__title">{{ t('ui.storage') }}</h3>
      <div class="storage-bar">
        <div class="storage-bar__fill" :style="{ width: `${storagePercent}%`, background: storageColor }" />
      </div>
      <div class="storage-info">
        <span class="font-mono">{{ formatSize(auth.user?.storageUsed || 0) }}</span>
        <span class="text-muted"> / {{ formatSize(auth.user?.storageQuota || 0) }}</span>
        <span style="margin-left:8px;font-size:12px" :style="{ color: storageColor }">{{ storagePercent }}%</span>
      </div>
    </div>

    <div class="section">
      <div class="section__header">
        <h3 class="section__title">WebDAV</h3>
        <span class="webdav-status" :class="{ 'webdav-status--on': webdavEnabled }">
          {{ webdavEnabled ? t('common.enabled') : t('common.disabled') }}
        </span>
      </div>
      <p class="section__hint">{{ t('profile.webdavHint') }}</p>
      <div class="webdav-form">
        <OInput
          v-model="webdavPassword"
          type="password"
          :label="webdavEnabled ? t('profile.newWebdavPassword') : t('profile.webdavPassword')"
          :placeholder="t('profile.passwordPlaceholder')"
          :disabled="webdavLoading"
        />
        <div class="webdav-actions">
          <OButton variant="primary" :loading="webdavLoading" @click="saveWebDavSettings(true)">
            {{ webdavEnabled ? t('profile.updatePassword') : t('profile.enableWebdav') }}
          </OButton>
          <OButton
            v-if="webdavEnabled"
            variant="danger"
            :disabled="webdavLoading"
            @click="saveWebDavSettings(false)"
          >
            {{ t('profile.disableWebdav') }}
          </OButton>
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section__header">
        <h3 class="section__title">{{ t('profile.teamNotifications') }}</h3>
      </div>
      <div class="notification-prefs">
        <label v-for="option in notificationPreferenceOptions" :key="option" class="notification-prefs__row">
          <span>{{ t(`profile.preference.${option}`) }}</span>
          <input
            type="checkbox"
            :checked="isNotificationEnabled(option)"
            :disabled="notificationPreferencesLoading"
            @change="event => toggleNotificationPreference(option, (event.target as HTMLInputElement).checked)"
          />
        </label>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-page { width: 100%; max-width: 1120px; margin: 0 auto; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 28px; align-content: start; }
.profile-page > h2, .profile-card, .profile-page > .section:nth-last-child(-n + 2) { grid-column: 1 / -1; }
.profile-card {
  display: flex; align-items: center; gap: 16px;
  padding: 20px; border: 1px solid var(--border); border-radius: 12px;
  background: var(--bg-elevated); margin-bottom: 28px;
}
.profile-card__avatar {
  width: 52px; height: 52px; border-radius: 12px;
  background: var(--accent-dim); border: 1px solid rgba(0,212,170,0.25);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 700; color: var(--accent);
}
.profile-card__name { font-size: 16px; font-weight: 600; margin-bottom: 2px; }
.profile-card__role { font-size: 12px; color: var(--text-secondary); }
.profile-card__email { font-size: 12px; color: var(--text-muted); margin-top: 3px; }

.section { margin-bottom: 28px; }
.section__title { font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.06em; }
.section__header { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.section__header .section__title { margin-bottom: 0; }
.section__hint { margin-bottom: 12px; color: var(--text-muted); font-size: 12px; line-height: 1.6; }
.theme-picker {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px;
  padding: 4px; border: 1px solid var(--border); border-radius: 9px;
  background: var(--bg-elevated);
}
.theme-picker__option {
  appearance: none; border: 1px solid transparent; border-radius: 6px;
  padding: 8px 10px; color: var(--text-secondary); background: transparent;
  font: inherit; cursor: pointer; transition: all var(--fast);
}
.theme-picker__option:hover { color: var(--text-primary); background: var(--bg-surface); }
.theme-picker__option--active {
  color: var(--accent); border-color: var(--border-focus); background: var(--accent-dim);
}
.theme-picker__option:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 1px; }
.theme-picker__hint { margin: 7px 0 0; }

.storage-bar { height: 6px; background: var(--border); border-radius: 3px; margin-bottom: 6px; overflow: hidden; }
.storage-bar__fill { height: 100%; border-radius: 3px; transition: width 600ms; }
.storage-info { font-size: 13px; }
.webdav-status {
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1;
  padding: 4px 8px;
}
.webdav-status--on {
  border-color: rgba(0, 212, 170, 0.35);
  color: var(--accent);
  background: var(--accent-dim);
}
.webdav-form { display: flex; flex-direction: column; gap: 12px; }
.webdav-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.notification-prefs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.notification-prefs__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  color: var(--text-secondary);
  font-size: 13px;
}
.notification-prefs__row input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
}
@media (max-width: 720px) {
  .profile-page { display: block; }
  .notification-prefs {
    grid-template-columns: 1fr;
  }
}
</style>
