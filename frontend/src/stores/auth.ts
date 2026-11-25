import { redirectToLogin, isQtRuntime } from '@/platform/environment'
import { onScopeDispose } from 'vue'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { UserEntity } from '@/types/user'
import { locale, resolveSupportedLocale, setLocale, type SupportedLocale } from '@/i18n'
import { toast } from 'vue-sonner'
import { i18n } from '@/i18n'
import { useTransferStore } from '@/stores/transfer'
import { useUploadStore } from '@/stores/upload'
import { clearSession, synchronizeSession } from '@/platform/session'
import { identityRevision, assertIdentity } from '@/platform/identity'
import { getApiBaseUrl } from '@/config/runtime'
import { revokeRemote } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserEntity | null>(null)
  const loading = ref(false)
  const initialized = ref(false)
  let initialization: { revision: number; promise: Promise<boolean> } | undefined
  const isLoggedIn = computed(() => !!user.value && !!localStorage.getItem('accessToken'))
  const isAdmin = computed(() => user.value?.role === 'ROLE_ADMIN')
  const storagePercent = computed(() => user.value?.storageQuota ? Math.min(100, Math.round(user.value.storageUsed / user.value.storageQuota * 100)) : 0)
  function reset() {
    user.value = null
    initialized.value = false
    useTransferStore().applyQueue({ tasks: [] })
  }
  window.addEventListener('bcd:identity-invalidated', reset)
  onScopeDispose(() => window.removeEventListener('bcd:identity-invalidated', reset))
  async function abandon(revision: number) {
    if (revision !== identityRevision()) return
    const clearing = clearSession('sync-failed')
    const clearedRevision = identityRevision()
    redirectToLogin()
    await clearing.catch(() => undefined)
    if (clearedRevision === identityRevision()) initialized.value = true
  }
  async function fetchMe(suppressToast = false) {
    const revision = identityRevision()
    try {
      const { data } = await authApi.getMe(suppressToast)
      assertIdentity(revision)
      user.value = data.data
      try { await setLocale(resolveSupportedLocale(data.data.preferredLocale) || 'en-US') } catch { /* Locale failure does not invalidate identity. */ }
      assertIdentity(revision)
      return true
    } catch {
      if (revision === identityRevision()) user.value = null
      return false
    }
  }
  async function login(username: string, password: string) {
    const clearing = clearSession('login')
    const revision = identityRevision()
    try {
      await clearing
      assertIdentity(revision)
      const { data } = await authApi.login(username, password)
      assertIdentity(revision)
      localStorage.setItem('accessToken', data.data.accessToken)
      localStorage.setItem('refreshToken', data.data.refreshToken)
      if (!await fetchMe()) throw new Error('AUTH_REQUIRED')
      assertIdentity(revision)
      await synchronizeSession()
      assertIdentity(revision)
      useUploadStore().activateIdentity(getApiBaseUrl(), user.value!.id)
      initialized.value = true
      return data
    } catch (error) { await abandon(revision); throw error }
  }
  function initializeSession(): Promise<boolean> {
    const revision = identityRevision()
    if (initialized.value) return Promise.resolve(!!localStorage.getItem('accessToken') && !!user.value)
    if (initialization?.revision === revision) return initialization.promise
    const entry = { revision, promise: undefined as unknown as Promise<boolean> }
    entry.promise = (async () => {
      try {
        if (!localStorage.getItem('accessToken')) { localStorage.removeItem('refreshToken'); user.value = null; return false }
        if (!await fetchMe(true)) throw new Error('AUTH_REQUIRED')
        assertIdentity(revision)
        await synchronizeSession()
        assertIdentity(revision)
        useUploadStore().activateIdentity(getApiBaseUrl(), user.value!.id)
        return true
      } catch { await abandon(revision); return false }
      finally {
        if (revision === identityRevision()) initialized.value = true
        if (initialization === entry) initialization = undefined
      }
    })()
    initialization = entry
    return entry.promise
  }
  async function logout() {
    const base = getApiBaseUrl(), token = localStorage.getItem('accessToken')
    // Clear web state before either network operation, even if the bridge fails.
    const clearing = clearSession()
    const revision = identityRevision()
    initialized.value = true
    const navigation = isQtRuntime() ? Promise.resolve(redirectToLogin()) : import('@/router').then(({ default: router }) => {
      if (revision === identityRevision()) return router.replace('/login')
    })
    await Promise.all([
      navigation,
      clearing.catch(() => { if (revision === identityRevision()) toast.error(i18n.global.t('file.client.networkConnectionFailedPleaseTryAgainLater')) }),
      revokeRemote(base, token).catch(() => { if (revision === identityRevision()) toast.error(i18n.global.t('auth.remoteLogoutUnconfirmed')) }),
    ])
  }
  const register = (username: string, password: string, email: string, verificationCode: string) => authApi.register(username, password, email, verificationCode, locale.value)
  async function updateWebDavSettings(enabled: boolean, password?: string) {
    const revision = identityRevision()
    const { data } = await authApi.updateWebDavSettings(enabled, password)
    assertIdentity(revision)
    user.value = data.data
    return data
  }
  async function updateLocale(next: SupportedLocale) {
    const revision = identityRevision(), previous = locale.value
    await setLocale(next)
    try {
      assertIdentity(revision)
      const { data } = await authApi.updateLocale(next)
      assertIdentity(revision)
      user.value = data.data
      return data
    } catch (error) {
      if (revision === identityRevision()) await setLocale(previous)
      throw error
    }
  }
  return { user, loading, initialized, isLoggedIn, isAdmin, storagePercent, login, register, fetchMe, initializeSession, logout, updateWebDavSettings, updateLocale }
})
