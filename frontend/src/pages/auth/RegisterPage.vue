<script setup lang="ts">
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useRouter } from 'vue-router'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useAuthStore } from '@/stores/auth'
import { toast } from 'vue-sonner'

const router = useRouter()
const auth = useAuthStore()

const schema = toTypedSchema(z.object({
  username: z.string().min(3, '用户名至少 3 个字符').max(20, '用户名最多 20 个字符'),
  email:    z.string().email('请输入有效的邮箱地址').optional().or(z.literal('')),
  password: z.string().min(6, '密码至少 6 个字符'),
  confirm:  z.string()
}).refine(d => d.password === d.confirm, { message: '两次密码不一致', path: ['confirm'] }))

const { handleSubmit, isSubmitting } = useForm({ validationSchema: schema })
const { value: username, errorMessage: usernameError } = useField<string>('username')
const { value: email,    errorMessage: emailError    } = useField<string>('email')
const { value: password, errorMessage: passwordError } = useField<string>('password')
const { value: confirm,  errorMessage: confirmError  } = useField<string>('confirm')

const submit = handleSubmit(async (values) => {
  try {
    await auth.register(values.username, values.password, values.email || undefined)
    toast.success('注册成功，请登录')
    router.push('/login')
  } catch { /* client.ts handles toast */ }
})
</script>

<template>
  <div class="reg-page page-enter">
    <div class="reg-page__head">
      <h1>创建账号</h1>
      <p>加入 BetterDrive</p>
    </div>
    <form class="reg-page__form" @submit.prevent="submit">
      <OInput v-model="username" label="用户名" placeholder="3-20 个字符" :error="usernameError" />
      <OInput v-model="email"    label="邮箱（可选）" placeholder="用于找回密码" type="email" :error="emailError" />
      <OInput v-model="password" label="密码" placeholder="至少 6 个字符" type="password" :error="passwordError" />
      <OInput v-model="confirm"  label="确认密码" placeholder="再次输入密码" type="password" :error="confirmError" />
      <OButton type="submit" variant="primary" :loading="isSubmitting" style="width:100%;justify-content:center">
        注册
      </OButton>
    </form>
    <div class="reg-page__links">
      已有账号？<RouterLink to="/login">立即登录</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.reg-page { width: 100%; max-width: 340px; }
.reg-page__head { margin-bottom: 24px; }
.reg-page__head h1 { font-size: 22px; margin-bottom: 4px; }
.reg-page__head p  { font-size: 13px; color: var(--text-secondary); }
.reg-page__form { display: flex; flex-direction: column; gap: 14px; margin-bottom: 20px; }
.reg-page__links { font-size: 13px; color: var(--text-secondary); }
</style>
