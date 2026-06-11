<script setup lang="ts">
import { ref, computed } from 'vue'
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from '@/composables/useFormatters'
import * as authApi from '@/api/auth'
import { toast } from 'vue-sonner'

const auth = useAuthStore()
const { formatSize } = useFormatters()

const storagePercent = computed(() => auth.storagePercent)
const storageColor = computed(() => storagePercent.value > 90 ? 'var(--danger)' : storagePercent.value > 70 ? 'var(--warning)' : 'var(--accent)')

// Email form
const emailSchema = toTypedSchema(z.object({ email: z.string().email('请输入有效邮箱').or(z.literal('')) }))
const { handleSubmit: saveEmail, isSubmitting: savingEmail } = useForm({ validationSchema: emailSchema, initialValues: { email: auth.user?.email || '' } })
const { value: email, errorMessage: emailError } = useField<string>('email')
const submitEmail = saveEmail(async (v) => {
  await authApi.updateProfile(v.email)
  await auth.fetchMe()
  toast.success('邮箱已更新')
})

// Password form
const pwSchema = toTypedSchema(z.object({
  oldPassword: z.string().min(1, '请输入当前密码'),
  newPassword: z.string().min(6, '新密码至少 6 个字符'),
  confirm: z.string()
}).refine(d => d.newPassword === d.confirm, { message: '两次密码不一致', path: ['confirm'] }))
const { handleSubmit: savePw, isSubmitting: savingPw, resetForm: resetPw } = useForm({ validationSchema: pwSchema })
const { value: oldPw, errorMessage: oldPwErr } = useField<string>('oldPassword')
const { value: newPw, errorMessage: newPwErr } = useField<string>('newPassword')
const { value: confirmPw, errorMessage: confirmPwErr } = useField<string>('confirm')
const submitPw = savePw(async (v) => {
  await authApi.changePassword(v.oldPassword, v.newPassword)
  toast.success('密码已修改')
  resetPw()
})
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

    <!-- Update email -->
    <div class="section">
      <h3 class="section__title">更新邮箱</h3>
      <form @submit.prevent="submitEmail" style="display:flex;gap:10px;align-items:flex-end">
        <OInput v-model="email" label="邮箱地址" placeholder="输入新邮箱" type="email" :error="emailError" style="flex:1" />
        <OButton type="submit" variant="primary" :loading="savingEmail">保存</OButton>
      </form>
    </div>

    <!-- Change password -->
    <div class="section">
      <h3 class="section__title">修改密码</h3>
      <form @submit.prevent="submitPw" style="display:flex;flex-direction:column;gap:14px">
        <OInput v-model="oldPw" label="当前密码" type="password" :error="oldPwErr" />
        <OInput v-model="newPw" label="新密码" type="password" :error="newPwErr" />
        <OInput v-model="confirmPw" label="确认新密码" type="password" :error="confirmPwErr" />
        <div>
          <OButton type="submit" variant="primary" :loading="savingPw">修改密码</OButton>
        </div>
      </form>
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

.section { margin-bottom: 28px; }
.section__title { font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.06em; }

.storage-bar { height: 6px; background: var(--border); border-radius: 3px; margin-bottom: 6px; overflow: hidden; }
.storage-bar__fill { height: 100%; border-radius: 3px; transition: width 600ms; }
.storage-info { font-size: 13px; }
</style>
