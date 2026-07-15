<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { ref } from 'vue'
import { useForm, useField } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import * as authApi from '@/api/auth'
import { toast } from 'vue-sonner'

const step = ref<'email' | 'reset'>('email')

const emailSchema = toTypedSchema(z.object({ email: z.string().email(__t('auth.forgotpassword.pleaseEnterAValidEmailAddress')) }))
const { handleSubmit: handleEmail, isSubmitting: emailSubmitting } = useForm({ validationSchema: emailSchema })
const { value: email, errorMessage: emailError } = useField<string>('email')

const resetSchema = toTypedSchema(z.object({
  code:        z.string().min(1, __t('auth.forgotpassword.pleaseEnterTheVerificationCode')),
  newPassword: z.string().min(6, __t('auth.forgotpassword.passwordMustBeAtLeast6Characters')),
}))
const { handleSubmit: handleReset, isSubmitting: resetSubmitting } = useForm({ validationSchema: resetSchema })
const { value: code,        errorMessage: codeError    } = useField<string>('code')
const { value: newPassword, errorMessage: passwordError } = useField<string>('newPassword')

const savedEmail = ref('')

const submitEmail = handleEmail(async (values) => {
  await authApi.forgotPassword(values.email)
  savedEmail.value = values.email
  toast.success(__t('auth.forgotpassword.verificationCodeHasBeenSentToEmail'))
  step.value = 'reset'
})

const submitReset = handleReset(async (values) => {
  await authApi.resetPassword(savedEmail.value, values.code, values.newPassword)
  toast.success(__t('auth.forgotpassword.passwordResetSuccessfulPleaseLogIn'))
  window.location.href = '/login'
})
</script>

<template>
  <div class="forgot-page page-enter">
    <div class="forgot-page__head">
      <h1>{{ __t('auth.forgotpassword.retrievePassword') }}</h1>
      <p>{{ step === 'email' ? __t('auth.forgotpassword.enterYourRegisteredEmailToReceiveTheVerificationCode') : __t('auth.forgotpassword.verificationCodeSentTo', { p0: savedEmail }) }}</p>
    </div>

    <form v-if="step === 'email'" class="forgot-page__form" @submit.prevent="submitEmail">
      <OInput v-model="email" :label="__t('auth.forgotpassword.emailAddress')" :placeholder="__t('auth.forgotpassword.enterRegisteredEmail')" type="email" :error="emailError" />
      <OButton type="submit" variant="primary" :loading="emailSubmitting" style="width:100%;justify-content:center">{{ __t('auth.forgotpassword.sendVerificationCode') }}</OButton>
    </form>

    <form v-else class="forgot-page__form" @submit.prevent="submitReset">
      <OInput v-model="code"        :label="__t('auth.forgotpassword.verificationCode')"  :placeholder="__t('auth.forgotpassword.enterTheVerificationCodeInTheEmail')" :error="codeError" />
      <OInput v-model="newPassword" :label="__t('auth.forgotpassword.newPassword')"  :placeholder="__t('auth.forgotpassword.atLeast6Characters')" type="password" :error="passwordError" />
      <OButton type="submit" variant="primary" :loading="resetSubmitting" style="width:100%;justify-content:center">{{ __t('auth.forgotpassword.resetPassword') }}</OButton>
      <OButton type="button" variant="ghost" style="width:100%;justify-content:center" @click="step = 'email'">{{ __t('auth.forgotpassword.resend') }}</OButton>
    </form>

    <div class="forgot-page__links">
      <RouterLink to="/login">{{ __t('auth.forgotpassword.returnToLogin') }}</RouterLink>
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
