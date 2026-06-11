<script setup lang="ts">
import { ref } from 'vue'
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import * as authApi from '@/api/auth'
import { toast } from 'vue-sonner'

const step = ref<'email' | 'reset'>('email')

const emailSchema = toTypedSchema(z.object({ email: z.string().email('请输入有效邮箱') }))
const { handleSubmit: handleEmail, isSubmitting: emailSubmitting } = useForm({ validationSchema: emailSchema })
const { value: email, errorMessage: emailError } = useField<string>('email')

const resetSchema = toTypedSchema(z.object({
  code:        z.string().min(1, '请输入验证码'),
  newPassword: z.string().min(6, '密码至少 6 个字符'),
}))
const { handleSubmit: handleReset, isSubmitting: resetSubmitting } = useForm({ validationSchema: resetSchema })
const { value: code,        errorMessage: codeError    } = useField<string>('code')
const { value: newPassword, errorMessage: passwordError } = useField<string>('newPassword')

const savedEmail = ref('')

const submitEmail = handleEmail(async (values) => {
  await authApi.forgotPassword(values.email)
  savedEmail.value = values.email
  toast.success('验证码已发送至邮箱')
  step.value = 'reset'
})

const submitReset = handleReset(async (values) => {
  await authApi.resetPassword(savedEmail.value, values.code, values.newPassword)
  toast.success('密码重置成功，请登录')
  window.location.href = '/login'
})
</script>

<template>
  <div class="forgot-page page-enter">
    <div class="forgot-page__head">
      <h1>找回密码</h1>
      <p>{{ step === 'email' ? '输入注册邮箱接收验证码' : `验证码已发送到 ${savedEmail}` }}</p>
    </div>

    <form v-if="step === 'email'" class="forgot-page__form" @submit.prevent="submitEmail">
      <OInput v-model="email" label="邮箱地址" placeholder="输入注册邮箱" type="email" :error="emailError" />
      <OButton type="submit" variant="primary" :loading="emailSubmitting" style="width:100%;justify-content:center">
        发送验证码
      </OButton>
    </form>

    <form v-else class="forgot-page__form" @submit.prevent="submitReset">
      <OInput v-model="code"        label="验证码"  placeholder="输入邮件中的验证码" :error="codeError" />
      <OInput v-model="newPassword" label="新密码"  placeholder="至少 6 个字符" type="password" :error="passwordError" />
      <OButton type="submit" variant="primary" :loading="resetSubmitting" style="width:100%;justify-content:center">
        重置密码
      </OButton>
      <OButton type="button" variant="ghost" style="width:100%;justify-content:center" @click="step = 'email'">
        重新发送
      </OButton>
    </form>

    <div class="forgot-page__links">
      <RouterLink to="/login">← 返回登录</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.forgot-page { width: 100%; max-width: 340px; }
.forgot-page__head { margin-bottom: 24px; }
.forgot-page__head h1 { font-size: 22px; margin-bottom: 4px; }
.forgot-page__head p  { font-size: 13px; color: var(--text-secondary); }
.forgot-page__form { display: flex; flex-direction: column; gap: 14px; margin-bottom: 20px; }
.forgot-page__links { font-size: 13px; }
</style>
