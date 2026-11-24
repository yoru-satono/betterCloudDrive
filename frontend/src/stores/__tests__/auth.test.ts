import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { revokeRemote } from '@/api/client'
import { identityRevision } from '@/platform/identity'
import { locale } from '@/i18n'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getMe: vi.fn(),
  logout: vi.fn(),
  updateWebDavSettings: vi.fn(),
  updateLocale: vi.fn(),
}))

vi.mock('@/api/client', async importOriginal => ({ ...await importOriginal<typeof import('@/api/client')>(), revokeRemote: vi.fn().mockResolvedValue(undefined) }))

const user = {
  id: 7,
  username: 'alice',
  email: 'alice@test.local',
  role: 'ROLE_ADMIN',
  status: 1,
  storageUsed: 25,
  storageQuota: 100,
  preferredLocale: 'zh-Hans',
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('persists tokens and loads the current user after login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({ data: { data: {
      accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 1800,
    } } } as never)
    vi.mocked(authApi.getMe).mockResolvedValue({ data: { data: user } } as never)
    const store = useAuthStore()

    await store.login('alice', 'secret')

    expect(localStorage.getItem('accessToken')).toBe('access')
    expect(localStorage.getItem('refreshToken')).toBe('refresh')
    expect(store.user?.username).toBe('alice')
    expect(store.isAdmin).toBe(true)
    expect(store.storagePercent).toBe(25)
  })

  it('clears stale user state when fetching the profile fails', async () => {
    vi.mocked(authApi.getMe).mockRejectedValue(new Error('unauthorized'))
    const store = useAuthStore()
    store.user = user as never

    await store.fetchMe()

    expect(store.user).toBeNull()
  })

  it('clears stale credentials when startup session validation fails', async () => {
    localStorage.setItem('accessToken', 'stale-access')
    localStorage.setItem('refreshToken', 'stale-refresh')
    vi.mocked(authApi.getMe).mockRejectedValue(new Error('unauthorized'))
    const store = useAuthStore()

    await expect(store.initializeSession()).resolves.toBe(false)

    expect(authApi.getMe).toHaveBeenCalledWith(true)
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(store.user).toBeNull()
    expect(store.initialized).toBe(true)
  })

  it('shares one profile request across concurrent startup checks', async () => {
    localStorage.setItem('accessToken', 'access')
    let resolveProfile: ((value: unknown) => void) | undefined
    vi.mocked(authApi.getMe).mockReturnValue(new Promise(resolve => {
      resolveProfile = resolve
    }) as never)
    const store = useAuthStore()

    const first = store.initializeSession()
    const second = store.initializeSession()
    resolveProfile?.({ data: { data: user } })

    await expect(Promise.all([first, second])).resolves.toEqual([true, true])
    expect(authApi.getMe).toHaveBeenCalledTimes(1)
    expect(store.user?.username).toBe('alice')
  })

  it('clears an orphan refresh token without requesting the profile', async () => {
    localStorage.setItem('refreshToken', 'orphan-refresh')
    const store = useAuthStore()

    await expect(store.initializeSession()).resolves.toBe(false)

    expect(authApi.getMe).not.toHaveBeenCalled()
    expect(localStorage.getItem('refreshToken')).toBeNull()
  })

  it('clears local credentials even when remote logout fails', async () => {
    localStorage.setItem('accessToken', 'access')
    localStorage.setItem('refreshToken', 'refresh')
    vi.mocked(revokeRemote).mockRejectedValueOnce(new Error('offline'))
    const store = useAuthStore()
    store.user = user as never

    await store.logout()

    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(store.user).toBeNull()
    expect(revokeRemote).toHaveBeenCalledWith('/api/v1', 'access')
  })

  it('loads the authenticated user locale preference', async () => {
    vi.mocked(authApi.getMe).mockResolvedValue({
      data: { data: { ...user, preferredLocale: 'en-US' } },
    } as never)
    const store = useAuthStore()

    await store.fetchMe()

    expect(locale.value).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
  })

  it.each(['zh-CN', 'zh-SG'])('maps regional simplified Chinese %s to the existing web dictionary', async (preferredLocale) => {
    vi.mocked(authApi.getMe).mockResolvedValue({ data: { data: { ...user, preferredLocale } } } as never)
    const store = useAuthStore()

    await store.fetchMe()

    expect(locale.value).toBe('zh-Hans')
    expect(document.documentElement.lang).toBe('zh-Hans')
  })

  it('restores the previous locale when preference persistence fails', async () => {
    vi.mocked(authApi.updateLocale).mockRejectedValue(new Error('offline'))
    const store = useAuthStore()

    await expect(store.updateLocale('en-US')).rejects.toThrow('offline')

    expect(locale.value).toBe('zh-Hans')
    expect(localStorage.getItem('preferredLocale')).toBe('zh-Hans')
  })
  it('clears identity synchronously while remote logout is still pending', async () => {
    let finish!: () => void
    vi.mocked(revokeRemote).mockReturnValueOnce(new Promise<void>(resolve => { finish = resolve }))
    localStorage.setItem('accessToken', 'old-access')
    const store = useAuthStore(); store.user = user as never
    const revision = identityRevision(), loggingOut = store.logout()
    expect(identityRevision()).toBeGreaterThan(revision)
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(store.user).toBeNull()
    finish(); await loggingOut
  })
  it('discards a profile returned after logout', async () => {
    let finish!: (value: any) => void
    vi.mocked(authApi.getMe).mockReturnValueOnce(new Promise(resolve => { finish = resolve }) as never)
    const store = useAuthStore(), fetching = store.fetchMe()
    await store.logout()
    finish({ data: { data: user } })
    expect(await fetching).toBe(false)
    expect(store.user).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

})
