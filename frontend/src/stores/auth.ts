import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { UserEntity } from '@/types/user'
import { locale, resolveSupportedLocale, setLocale, type SupportedLocale } from '@/i18n'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserEntity | null>(null)
  const loading = ref(false)
  const initialized = ref(false)
  let initializationPromise: Promise<boolean> | null = null

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
    const authenticated = await fetchMe()
    initialized.value = true
    if (!authenticated) {
      clearLocalSession()
      throw new Error()
    }
    return data
  }

  async function register(username: string, password: string, email: string, verificationCode: string) {
    return authApi.register(username, password, email, verificationCode, locale.value)
  }

  function clearLocalSession() {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    user.value = null
  }

  async function fetchMe(suppressToast = false) {
    let currentUser: UserEntity
    try {
      const { data } = await authApi.getMe(suppressToast)
      currentUser = data.data
    } catch {
      user.value = null
      return false
    }
    user.value = currentUser
    try {
      await setLocale(resolveSupportedLocale(currentUser.preferredLocale) || 'en-US')
    } catch (error) {
      console.warn('Failed to load the preferred locale', error)
    }
    return true
  }

  async function initializeSession() {
    const accessToken = localStorage.getItem('accessToken')
    if (initialized.value) {
      if (!accessToken) user.value = null
      return !!accessToken && !!user.value
    }
    if (initializationPromise) return initializationPromise

    initializationPromise = (async () => {
      if (!accessToken) {
        clearLocalSession()
        return false
      }

      const authenticated = await fetchMe(true)
      if (!authenticated) clearLocalSession()
      return authenticated
    })().finally(() => {
      initialized.value = true
      initializationPromise = null
    })

    return initializationPromise
  }

  async function logout() {
    try { await authApi.logout() } catch { /* ignore */ }
    clearLocalSession()
    initialized.value = true
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
    user, loading, initialized, isLoggedIn, isAdmin, storagePercent,
    login, register, fetchMe, initializeSession, logout, updateWebDavSettings, updateLocale
  }
})
