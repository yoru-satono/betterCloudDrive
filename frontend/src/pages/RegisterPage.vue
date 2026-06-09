<template>
  <div class="animate-fade-in">
    <h2 class="page-title">创建账户</h2>
    <p class="page-sub">注册 BetterCloudDrive</p>
    <form @submit.prevent="handleRegister" class="form">
      <div class="form-group"><label>用户名</label><input v-model="username" type="text" class="input" placeholder="3-64 字符" /></div>
      <div class="form-group"><label>邮箱（可选）</label><input v-model="email" type="email" class="input" placeholder="your@email.com" /></div>
      <div class="form-group"><label>密码</label><input v-model="password" type="password" class="input" placeholder="至少8位，含大小写字母+数字" /></div>
      <div class="form-group"><label>确认密码</label><input v-model="confirm" type="password" class="input" placeholder="再次输入密码" /></div>
      <p v-if="error" class="error-msg">{{ error }}</p>
      <button type="submit" class="btn btn-primary btn-full" :disabled="loading">{{ loading ? '注册中...' : '注册' }}</button>
    </form>
    <p class="form-footer"><router-link to="/login">已有账户？登录</router-link></p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
const router = useRouter()
const auth = useAuthStore()
const username = ref(''); const email = ref(''); const password = ref(''); const confirm = ref('')
const error = ref(''); const loading = ref(false)
async function handleRegister() {
  error.value = ''
  if (password.value !== confirm.value) { error.value = '两次密码不一致'; return }
  loading.value = true
  try { await auth.register(username.value, password.value, email.value || undefined); await auth.login(username.value, password.value); router.push('/files') }
  catch (e: any) { error.value = e.response?.data?.message || '注册失败' }
  finally { loading.value = false }
}
</script>

<style scoped>
.page-title { text-align: center; font-size: 24px; }
.page-sub { text-align: center; color: var(--text-secondary); margin-top: 6px; margin-bottom: 28px; }
.form { display: flex; flex-direction: column; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group label { font-size: 12px; font-weight: 500; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
.error-msg { color: var(--red); font-size: 13px; text-align: center; }
.btn-full { width: 100%; justify-content: center; padding: 12px; font-size: 15px; }
.form-footer { text-align: center; margin-top: 20px; font-size: 13px; }
</style>
