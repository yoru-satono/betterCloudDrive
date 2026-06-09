import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<any>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!localStorage.getItem('accessToken'))
  const isAdmin = computed(() => user.value?.role === 'ROLE_ADMIN')
  const storagePercent = computed(() => {
    if (!user.value) return 0
    return Math.round((user.value.storageUsed / user.value.storageQuota) * 100)
  })

  async function login(username: string, password: string) {
    const { data } = await api.post('/auth/login', { username, password })
    const d = data.data
    localStorage.setItem('accessToken', d.accessToken)
    localStorage.setItem('refreshToken', d.refreshToken)
    await fetchMe()
    return data
  }

  async function register(username: string, password: string, email?: string) {
    return api.post('/auth/register', { username, password, email })
  }

  async function fetchMe() {
    try {
      const { data } = await api.get('/auth/me')
      user.value = data.data
    } catch { user.value = null }
  }

  async function logout() {
    try { await api.post('/auth/logout') } catch { /* ignore */ }
    localStorage.clear()
    user.value = null
  }

  async function sendVerificationCode() {
    return api.post('/auth/verify-email/send')
  }

  async function verifyEmail(code: string) {
    return api.post('/auth/verify-email/confirm', { code })
  }

  async function forgotPassword(email: string) {
    return api.post('/auth/forgot-password', { email })
  }

  async function resetPassword(email: string, code: string, newPassword: string) {
    return api.post('/auth/reset-password', { email, code, newPassword })
  }

  return { user, loading, isLoggedIn, isAdmin, storagePercent, login, register, fetchMe, logout, sendVerificationCode, verifyEmail, forgotPassword, resetPassword }
})
