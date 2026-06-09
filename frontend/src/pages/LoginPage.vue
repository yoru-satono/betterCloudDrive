<template>
  <div class="login-page">
    <h2 class="page-title">欢迎回来</h2>
    <p class="page-sub">登录您的 BetterCloudDrive 账户</p>

    <form @submit.prevent="handleLogin" class="form">
      <div class="form-group">
        <label>用户名</label>
        <input v-model="username" type="text" class="input" placeholder="输入用户名" autocomplete="username" />
      </div>
      <div class="form-group">
        <label>密码</label>
        <input v-model="password" type="password" class="input" placeholder="输入密码" autocomplete="current-password" />
      </div>
      <p v-if="error" class="error-msg">{{ error }}</p>
      <button type="submit" class="btn btn-primary btn-full" :disabled="loading">
        {{ loading ? '登录中...' : '登录' }}
      </button>
    </form>

    <div class="form-footer">
      <router-link to="/register">没有账户？注册</router-link>
      <router-link to="/forgot-password">忘记密码？</router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/files')
  } catch (e: any) {
    error.value = e.response?.data?.message || '登录失败'
  } finally { loading.value = false }
}
</script>

<style scoped>
.page-title { text-align: center; font-size: 24px; }
.page-sub { text-align: center; color: var(--text-secondary); margin-top: 6px; margin-bottom: 28px; font-size: 14px; }
.form { display: flex; flex-direction: column; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group label { font-size: 12px; font-weight: 500; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
.error-msg { color: var(--red); font-size: 13px; text-align: center; }
.btn-full { width: 100%; justify-content: center; padding: 12px; font-size: 15px; }
.form-footer { display: flex; justify-content: space-between; margin-top: 20px; font-size: 13px; }
</style>
