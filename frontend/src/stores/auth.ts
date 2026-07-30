import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { UserEntity } from '@/types/user'
import { locale, setLocale, type SupportedLocale } from '@/i18n'

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
    await fetchMe()
    return data
  }

  async function register(username: string, password: string, email: string, verificationCode: string) {
    return authApi.register(username, password, email, verificationCode, locale.value)
  }

  async function fetchMe() {
    let currentUser: UserEntity
    try {
      const { data } = await authApi.getMe()
      currentUser = data.data
    } catch {
      user.value = null
      return
    }
    user.value = currentUser
    try {
      await setLocale(currentUser.preferredLocale || 'zh-CN')
    } catch (error) {
      console.warn('Failed to load the preferred locale', error)
    }
  }

  async function logout() {
    try { await authApi.logout() } catch { /* ignore */ }
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    user.value = null
  }

  async function updateWebDavSettings(enabled: boolean, password?: string) {
    const { data } = await authApi.updateWebDavSettings(enabled, password)
    user.value = data.data
    return data
  }

  async function updateLocale(next: SupportedLocale) {
    const previous = locale.value
    await setLocale(next)
    try {
      const { data } = await authApi.updateLocale(next)
      user.value = data.data
      return data
    } catch (error) {
      await setLocale(previous)
      throw error
    }
  }

  return {
    user, loading, isLoggedIn, isAdmin, storagePercent,
    login, register, fetchMe, logout, updateWebDavSettings, updateLocale
  }
})
