<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import * as teamsApi from '@/api/teams'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import { toast } from 'vue-sonner'
import type { TeamInvite } from '@/types/team'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { formatDateFull } = useFormatters()

const loading = ref(true)
const invite = ref<TeamInvite | null>(null)
const token = computed(() => String(route.params.token || ''))
const loggedIn = computed(() => !!localStorage.getItem('accessToken'))

async function load() {
  loading.value = true
  try {
    const { data } = await teamsApi.getInvite(token.value)
    invite.value = data.data
    if (loggedIn.value && !auth.user) await auth.fetchMe().catch(() => {})
  } finally {
    loading.value = false
  }
}

async function accept() {
  await teamsApi.acceptInvite(token.value)
  toast.success('已加入团队')
  await router.push(invite.value ? `/teams/${invite.value.teamId}/files` : '/files')
}

async function reject() {
  await teamsApi.rejectInvite(token.value)
  toast.success('已拒绝邀请')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="invite-page">
    <div v-if="loading" class="invite-page__loading"><OSpinner /></div>
    <div v-else-if="invite" class="invite-panel">
      <div class="invite-panel__eyebrow">团队邀请</div>
      <h1>{{ invite.teamName || '团队' }}</h1>
      <p>{{ invite.invitedByUsername || '团队管理员' }} 邀请 {{ invite.email }} 以 {{ invite.role }} 身份加入。</p>
      <div class="invite-panel__meta">
        <span>状态：{{ invite.status }}</span>
        <span>过期：{{ formatDateFull(invite.expiresAt) }}</span>
      </div>
      <div v-if="invite.status === 'PENDING'" class="invite-panel__actions">
        <template v-if="loggedIn">
          <OButton variant="primary" @click="accept">接受邀请</OButton>
          <OButton variant="ghost" @click="reject">拒绝</OButton>
        </template>
        <template v-else>
          <OButton variant="primary" @click="router.push('/login')">登录后接受</OButton>
          <OButton variant="ghost" @click="router.push('/register')">注册账号</OButton>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.invite-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--bg-base);
}
.invite-page__loading {
  display: flex;
  justify-content: center;
  padding: 40px;
}
.invite-panel {
  width: min(520px, 100%);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-surface);
  padding: 28px;
}
.invite-panel__eyebrow {
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}
.invite-panel h1 {
  margin: 0;
  font-size: 24px;
}
.invite-panel p {
  color: var(--text-secondary);
  line-height: 1.6;
}
.invite-panel__meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--text-muted);
  font-size: 12px;
  margin: 16px 0;
}
.invite-panel__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
