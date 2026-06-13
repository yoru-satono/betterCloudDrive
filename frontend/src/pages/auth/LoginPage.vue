<script setup lang="ts">
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useAuthStore } from '@/stores/auth'
import { toast } from 'vue-sonner'

const router = useRouter()
const auth = useAuthStore()

const schema = toTypedSchema(z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
}))

const { handleSubmit, isSubmitting } = useForm({ validationSchema: schema })
const { value: username, errorMessage: usernameError } = useField<string>('username')
const { value: password, errorMessage: passwordError } = useField<string>('password')
const showPassword = ref(false)

const submit = handleSubmit(async (values) => {
  try {
    await auth.login(values.username, values.password)
    toast.success('登录成功')
    router.push('/files')
  } catch { /* client.ts handles toast */ }
})
</script>

<template>
  <div class="login-page page-enter">
    <div class="login-page__head">
      <div class="login-page__logo">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2">
          <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
          <polyline points="9 22 9 12 15 12 15 22"/>
        </svg>
      </div>
      <h1 class="login-page__title">欢迎回来</h1>
      <p class="login-page__sub">登录到 BetterCloudDrive</p>
    </div>

    <form class="login-page__form" @submit.prevent="submit">
      <OInput
        v-model="username"
        label="用户名"
        placeholder="输入用户名"
        :error="usernameError"
        autocomplete="username"
      />
      <OInput
        v-model="password"
        label="密码"
        :type="showPassword ? 'text' : 'password'"
        placeholder="输入密码"
        :error="passwordError"
        autocomplete="current-password"
      >
        <template #suffix>
          <button type="button" class="pass-toggle" @click="showPassword = !showPassword">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path v-if="showPassword" d="M17.9 17.9A10 10 0 0112 20c-5 0-9.3-3.1-11-7.5a11 11 0 012.9-4.4M6.2 6.2A10 10 0 0112 4c5 0 9.3 3.1 11 7.5a11 11 0 01-4 5.1M1 1l22 22"/>
              <path v-else d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle v-if="!showPassword" cx="12" cy="12" r="3"/>
            </svg>
          </button>
        </template>
      </OInput>

      <OButton type="submit" variant="primary" :loading="isSubmitting" style="width:100%;justify-content:center">
        登录
      </OButton>
    </form>

    <div class="login-page__links">
      <RouterLink to="/forgot-password">忘记密码？</RouterLink>
      <span>·</span>
      <RouterLink to="/register">注册账号</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.login-page { width: 100%; max-width: 340px; }
.login-page__head { margin-bottom: 28px; }
.login-page__logo {
  width: 40px; height: 40px;
  background: var(--accent-dim); border: 1px solid rgba(0,212,170,0.2);
  border-radius: 10px; display: flex; align-items: center; justify-content: center;
  margin-bottom: 20px;
}
.login-page__title { font-size: 22px; margin-bottom: 4px; }
.login-page__sub { font-size: 13px; color: var(--text-secondary); }
.login-page__form { display: flex; flex-direction: column; gap: 14px; margin-bottom: 20px; }
.login-page__links { display: flex; gap: 8px; font-size: 13px; color: var(--text-secondary); }
.pass-toggle { background: none; border: none; cursor: pointer; color: var(--text-muted); display: flex; align-items: center; }
.pass-toggle:hover { color: var(--text-secondary); }
</style>
