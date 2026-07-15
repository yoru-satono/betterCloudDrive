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
import { i18n, locale } from '@/i18n'

const router = useRouter()
const auth = useAuthStore()
const t = i18n.global.t

const schema = computed(() => toTypedSchema(z.object({
  username: z.string().min(3, t('validation.usernameMin')).max(20, t('validation.usernameMax')),
  email:    z.string().min(1, t('validation.emailRequired')).email(t('validation.emailInvalid')),
  verificationCode: z.string().regex(/^\d{6}$/, t('validation.codeSixDigits')),
  password: z.string().min(6, t('validation.passwordMin')),
  confirm:  z.string()
}).refine(d => d.password === d.confirm, { message: t('validation.passwordMismatch'), path: ['confirm'] })))

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
    toast.error(t('validation.emailFirst'))
    return
  }
  sendingCode.value = true
  try {
    await authApi.sendRegistrationCode(currentEmail, locale.value)
    toast.success(t('ui.codeSent'))
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
    toast.success(t('ui.registrationSuccess'))
    router.push('/login')
  } catch { /* client.ts handles toast */ }
})
</script>

<template>
  <div class="reg-page page-enter">
    <div class="reg-page__head">
      <h1>{{ t('ui.createAccount') }}</h1>
      <p>{{ t('ui.join') }}</p>
    </div>
    <form class="reg-page__form" @submit.prevent="submit">
      <OInput v-model="username" :label="t('ui.username')" :placeholder="t('validation.usernameLength')" :error="usernameError" />
      <OInput v-model="email" :label="t('ui.email')" :placeholder="t('ui.emailHint')" type="email" :error="emailError" />
      <div class="reg-page__code">
        <OInput
          v-model="verificationCode"
          :label="t('ui.verificationCode')"
          :placeholder="t('ui.sixDigitCode')"
          inputmode="numeric"
          :error="verificationCodeError"
        />
        <OButton type="button" variant="ghost" :loading="sendingCode" :disabled="!canSendCode" @click="sendCode">
          {{ codeCooldown > 0 ? `${codeCooldown}s` : t('ui.sendCode') }}
        </OButton>
      </div>
      <OInput v-model="password" :label="t('ui.password')" :placeholder="t('validation.passwordLength')" type="password" :error="passwordError" />
      <OInput v-model="confirm" :label="t('ui.confirmPassword')" :placeholder="t('ui.enterAgain')" type="password" :error="confirmError" />
      <OButton type="submit" variant="primary" :loading="isSubmitting" style="width:100%;justify-content:center">
        {{ t('ui.register') }}
      </OButton>
    </form>
    <div class="reg-page__links">
      {{ t('ui.alreadyAccount') }} <RouterLink to="/login">{{ t('ui.signInNow') }}</RouterLink>
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
