import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import api from '@/api/client'

vi.mock('@/api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } }
  }
}))

describe('Auth Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('login should store tokens and fetch user', async () => {
    const mockPost = api.post as any
    mockPost.mockResolvedValueOnce({
      data: { data: { accessToken: 'at', refreshToken: 'rt' } }
    })
    const mockGet = api.get as any
    mockGet.mockResolvedValueOnce({
      data: { data: { id: 1, username: 'test', role: 'ROLE_USER', storageUsed: 0, storageQuota: 10737418240 } }
    })

    const store = useAuthStore()
    await store.login('test', 'Pass123!')

    expect(localStorage.getItem('accessToken')).toBe('at')
    expect(localStorage.getItem('refreshToken')).toBe('rt')
    expect(store.user?.username).toBe('test')
  })

  it('fetchMe should set user from API', async () => {
    const mockGet = api.get as any
    mockGet.mockResolvedValueOnce({
      data: { data: { id: 2, username: 'alice', role: 'ROLE_USER', storageUsed: 5242880, storageQuota: 10737418240 } }
    })
    localStorage.setItem('accessToken', 'token')

    const store = useAuthStore()
    await store.fetchMe()

    expect(store.user?.username).toBe('alice')
    expect(store.storagePercent).toBe(0) // 5MB / 10GB ≈ 0%
  })

  it('logout should clear storage and user', async () => {
    const mockPost = api.post as any
    mockPost.mockResolvedValueOnce({ data: {} })
    localStorage.setItem('accessToken', 'at')
    localStorage.setItem('refreshToken', 'rt')

    const store = useAuthStore()
    store.user = { id: 1, username: 'test' } as any
    await store.logout()

    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(store.user).toBeNull()
  })

  it('isAdmin should reflect role', () => {
    const store = useAuthStore()
    store.user = { role: 'ROLE_USER' } as any
    expect(store.isAdmin).toBe(false)
    store.user = { role: 'ROLE_ADMIN' } as any
    expect(store.isAdmin).toBe(true)
  })

  it('storagePercent should compute correctly', () => {
    const store = useAuthStore()
    store.user = { storageUsed: 5368709120, storageQuota: 10737418240 } as any // 5GB / 10GB
    expect(store.storagePercent).toBe(50)
  })
})
