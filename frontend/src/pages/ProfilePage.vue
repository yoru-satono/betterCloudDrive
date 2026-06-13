<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'

const auth = useAuthStore()
const { formatSize } = useFormatters()

const storagePercent = computed(() => auth.storagePercent)
const storageColor = computed(() => storagePercent.value > 90 ? 'var(--danger)' : storagePercent.value > 70 ? 'var(--warning)' : 'var(--accent)')
</script>

<template>
  <div class="profile-page page-enter">
    <h2 style="margin-bottom:24px">个人资料</h2>

    <!-- User info card -->
    <div class="profile-card">
      <div class="profile-card__avatar">{{ auth.user?.username?.[0]?.toUpperCase() }}</div>
      <div class="profile-card__info">
        <div class="profile-card__name">{{ auth.user?.username }}</div>
        <div class="profile-card__role">{{ auth.isAdmin ? '管理员' : '普通用户' }}</div>
        <div class="profile-card__email">{{ auth.user?.email || '未绑定邮箱' }}</div>
      </div>
    </div>

    <!-- Storage usage -->
    <div class="section">
      <h3 class="section__title">存储空间</h3>
      <div class="storage-bar">
        <div class="storage-bar__fill" :style="{ width: `${storagePercent}%`, background: storageColor }" />
      </div>
      <div class="storage-info">
        <span class="font-mono">{{ formatSize(auth.user?.storageUsed || 0) }}</span>
        <span class="text-muted"> / {{ formatSize(auth.user?.storageQuota || 0) }}</span>
        <span style="margin-left:8px;font-size:12px" :style="{ color: storageColor }">{{ storagePercent }}%</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-page { max-width: 560px; }
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

.storage-bar { height: 6px; background: var(--border); border-radius: 3px; margin-bottom: 6px; overflow: hidden; }
.storage-bar__fill { height: 100%; border-radius: 3px; transition: width 600ms; }
.storage-info { font-size: 13px; }
</style>
