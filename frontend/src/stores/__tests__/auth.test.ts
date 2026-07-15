import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getMe: vi.fn(),
  logout: vi.fn(),
  updateWebDavSettings: vi.fn(),
  updateLocale: vi.fn(),
}))

const user = {
  id: 7,
  username: 'alice',
  email: 'alice@test.local',
  role: 'ROLE_ADMIN',
  status: 1,
  storageUsed: 25,
  storageQuota: 100,
  preferredLocale: 'zh-CN',
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

  it('clears local credentials even when remote logout fails', async () => {
    localStorage.setItem('accessToken', 'access')
    localStorage.setItem('refreshToken', 'refresh')
    vi.mocked(authApi.logout).mockRejectedValue(new Error('offline'))
    const store = useAuthStore()
    store.user = user as never

    await store.logout()

    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(store.user).toBeNull()
  })
})
