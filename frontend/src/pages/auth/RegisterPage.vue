<script setup lang="ts">
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useRouter } from 'vue-router'
import { computed, onBeforeUnmount, ref } from 'vue'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'
import { toast } from 'vue-sonner'

const router = useRouter()
const auth = useAuthStore()

const schema = toTypedSchema(z.object({
  username: z.string().min(3, '用户名至少 3 个字符').max(20, '用户名最多 20 个字符'),
  email:    z.string().min(1, '请输入邮箱地址').email('请输入有效的邮箱地址'),
  verificationCode: z.string().regex(/^\d{6}$/, '请输入 6 位验证码'),
  password: z.string().min(6, '密码至少 6 个字符'),
  confirm:  z.string()
}).refine(d => d.password === d.confirm, { message: '两次密码不一致', path: ['confirm'] }))

const { handleSubmit, isSubmitting } = useForm({ validationSchema: schema })
const { value: username, errorMessage: usernameError } = useField<string>('username')
const { value: email,    errorMessage: emailError    } = useField<string>('email')
const { value: verificationCode, errorMessage: verificationCodeError } = useField<string>('verificationCode')
const { value: password, errorMessage: passwordError } = useField<string>('password')
const { value: confirm,  errorMessage: confirmError  } = useField<string>('confirm')
const sendingCode = ref(false)
const codeCooldown = ref(0)
let cooldownTimer: number | undefined

const canSendCode = computed(() => !sendingCode.value && codeCooldown.value === 0)

function startCooldown() {
  codeCooldown.value = 60
  if (cooldownTimer) window.clearInterval(cooldownTimer)
  cooldownTimer = window.setInterval(() => {
    codeCooldown.value -= 1
    if (codeCooldown.value <= 0 && cooldownTimer) {
      window.clearInterval(cooldownTimer)
      cooldownTimer = undefined
    }
  }, 1000)
}

async function sendCode() {
  const currentEmail = email.value?.trim()
  if (!currentEmail) {
    toast.error('请先输入邮箱地址')
    return
  }
  sendingCode.value = true
  try {
    await authApi.sendRegistrationCode(currentEmail)
    toast.success('验证码已发送')
    startCooldown()
  } catch { /* client.ts handles toast */ }
  finally {
    sendingCode.value = false
  }
}

onBeforeUnmount(() => {
  if (cooldownTimer) window.clearInterval(cooldownTimer)
})

const submit = handleSubmit(async (values) => {
  try {
    await auth.register(values.username, values.password, values.email.trim(), values.verificationCode.trim())
    toast.success('注册成功，请登录')
    router.push('/login')
  } catch { /* client.ts handles toast */ }
})
</script>

<template>
  <div class="reg-page page-enter">
    <div class="reg-page__head">
      <h1>创建账号</h1>
      <p>加入 BetterCloudDrive</p>
    </div>
    <form class="reg-page__form" @submit.prevent="submit">
      <OInput v-model="username" label="用户名" placeholder="3-20 个字符" :error="usernameError" />
      <OInput v-model="email"    label="邮箱地址" placeholder="用于接收验证码和找回密码" type="email" :error="emailError" />
      <div class="reg-page__code">
        <OInput
          v-model="verificationCode"
          label="邮箱验证码"
          placeholder="6 位验证码"
          inputmode="numeric"
          :error="verificationCodeError"
        />
        <OButton type="button" variant="ghost" :loading="sendingCode" :disabled="!canSendCode" @click="sendCode">
          {{ codeCooldown > 0 ? `${codeCooldown}s` : '发送验证码' }}
        </OButton>
      </div>
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
.reg-page__code {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px;
  gap: 8px;
  align-items: end;
}
.reg-page__code :deep(.o-btn) {
  justify-content: center;
  height: 35px;
}
.reg-page__links { font-size: 13px; color: var(--text-secondary); }
</style>
