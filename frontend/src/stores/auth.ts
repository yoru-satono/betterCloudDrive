import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { UserEntity } from '@/types/user'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserEntity | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!localStorage.getItem('accessToken'))
  const isAdmin = computed(() => user.value?.role === 'ROLE_ADMIN')
  const storagePercent = computed(() => {
    if (!user.value || !user.value.storageQuota) return 0
    return Math.min(100, Math.round((user.value.storageUsed / user.value.storageQuota) * 100))
  })

  async function login(username: string, password: string) {
    const { data } = await authApi.login(username, password)
    localStorage.setItem('accessToken', data.data.accessToken)
    localStorage.setItem('refreshToken', data.data.refreshToken)
    user.value = data.data.user
    return data
  }

  async function register(username: string, password: string, email?: string) {
    return authApi.register(username, password, email)
  }

  async function fetchMe() {
    try {
      const { data } = await authApi.getMe()
      user.value = data.data
    } catch {
      user.value = null
    }
  }

  async function logout() {
    try { await authApi.logout() } catch { /* ignore */ }
    localStorage.clear()
    user.value = null
  }

  return {
    user, loading, isLoggedIn, isAdmin, storagePercent,
    login, register, fetchMe, logout
  }
})
