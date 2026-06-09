<template>
  <div class="animate-fade-in">
    <h2 class="page-title">重置密码</h2>
    <p class="page-sub">输入邮箱，我们将发送验证码</p>
    <form @submit.prevent="step === 1 ? sendCode() : doReset()" class="form">
      <template v-if="step === 1">
        <div class="form-group"><label>邮箱</label><input v-model="email" type="email" class="input" placeholder="your@email.com" /></div>
        <button class="btn btn-primary btn-full">发送验证码</button>
      </template>
      <template v-else>
        <div class="form-group"><label>验证码</label><input v-model="code" class="input" placeholder="6位数字" /></div>
        <div class="form-group"><label>新密码</label><input v-model="newPassword" type="password" class="input" /></div>
        <button class="btn btn-primary btn-full">重置密码</button>
      </template>
      <p v-if="msg" class="msg">{{ msg }}</p>
    </form>
    <p class="form-footer"><router-link to="/login">返回登录</router-link></p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
const email = ref(''); const code = ref(''); const newPassword = ref('')
const step = ref(1); const msg = ref('')
async function sendCode() { try { await auth.forgotPassword(email.value); msg.value = '验证码已发送'; step.value = 2 } catch { msg.value = '发送失败' } }
async function doReset() { try { await auth.resetPassword(email.value, code.value, newPassword.value); msg.value = '密码已重置，请登录' } catch { msg.value = '重置失败' } }
</script>

<style scoped>
.page-title { text-align: center; font-size: 24px; }
.page-sub { text-align: center; color: var(--text-secondary); margin-top: 6px; margin-bottom: 28px; }
.form { display: flex; flex-direction: column; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group label { font-size: 12px; font-weight: 500; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
.btn-full { width: 100%; justify-content: center; padding: 12px; }
.msg { text-align: center; font-size: 13px; color: var(--accent); }
.form-footer { text-align: center; margin-top: 20px; font-size: 13px; }
</style>
