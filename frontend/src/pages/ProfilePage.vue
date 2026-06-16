<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import { toast } from 'vue-sonner'

const auth = useAuthStore()
const { formatSize } = useFormatters()

const storagePercent = computed(() => auth.storagePercent)
const storageColor = computed(() => storagePercent.value > 90 ? 'var(--danger)' : storagePercent.value > 70 ? 'var(--warning)' : 'var(--accent)')
const webdavPassword = ref('')
const webdavLoading = ref(false)
const webdavEnabled = computed(() => Boolean(auth.user?.webdavEnabled))

async function saveWebDavSettings(enabled: boolean) {
  if (enabled && !webdavPassword.value.trim()) {
    toast.error('请设置 WebDAV 独立密码')
    return
  }
  webdavLoading.value = true
  try {
    await auth.updateWebDavSettings(enabled, enabled ? webdavPassword.value : undefined)
    webdavPassword.value = ''
    toast.success(enabled ? 'WebDAV 已开启' : 'WebDAV 已关闭')
  } finally {
    webdavLoading.value = false
  }
}
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

    <div class="section">
      <div class="section__header">
        <h3 class="section__title">WebDAV</h3>
        <span class="webdav-status" :class="{ 'webdav-status--on': webdavEnabled }">
          {{ webdavEnabled ? '已开启' : '已关闭' }}
        </span>
      </div>
      <p class="section__hint">WebDAV 默认关闭，开启时必须设置独立密码。</p>
      <div class="webdav-form">
        <OInput
          v-model="webdavPassword"
          type="password"
          :label="webdavEnabled ? '新的 WebDAV 密码' : 'WebDAV 密码'"
          placeholder="输入独立密码"
          :disabled="webdavLoading"
        />
        <div class="webdav-actions">
          <OButton variant="primary" :loading="webdavLoading" @click="saveWebDavSettings(true)">
            {{ webdavEnabled ? '更新密码' : '开启 WebDAV' }}
          </OButton>
          <OButton
            v-if="webdavEnabled"
            variant="danger"
            :disabled="webdavLoading"
            @click="saveWebDavSettings(false)"
          >
            关闭 WebDAV
          </OButton>
        </div>
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
.section__header { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.section__header .section__title { margin-bottom: 0; }
.section__hint { margin-bottom: 12px; color: var(--text-muted); font-size: 12px; line-height: 1.6; }

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
</style>
