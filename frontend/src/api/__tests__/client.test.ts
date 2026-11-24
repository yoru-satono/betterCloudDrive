import axios from 'axios'
import { invalidateIdentity } from '@/platform/identity'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { toast } from 'vue-sonner'
import api, { ensureFreshAccessToken, refreshAccessToken } from '@/api/client'

const isDesktopRuntime = vi.hoisted(() => vi.fn())

vi.mock('@/config/runtime', () => ({
  getApiBaseUrl: () => '/api/v1',
  isDesktopRuntime,
}))

vi.mock('vue-sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

vi.mock('@/platform/desktop', () => ({ waitForConnection: async () => {}, isConnectionConfirmed: () => true, rawInvoke: async () => {} }))

const adapter = vi.fn()
const axiosPost = vi.spyOn(axios, 'post')
const toastError = toast.error as Mock

beforeEach(() => {
  adapter.mockReset()
  axiosPost.mockReset()
  toastError.mockReset()
  api.defaults.adapter = adapter
  localStorage.clear()
  delete window.__BCD_RUNTIME__
  isDesktopRuntime.mockReturnValue(false)
  window.history.replaceState({}, '', '/')
})

const apiResponse = (data: unknown) => (config: unknown) => Promise.resolve({
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
  data,
})

const apiHttpError = (status: number, data: unknown = {}) => (config: unknown) => Promise.reject({
  message: 'Request failed',
  config,
  response: {
    status,
    statusText: 'Unauthorized',
    headers: {},
    config,
    data,
  },
})

describe('api client', () => {
  it('rejects backend business errors and shows a toast by default', async () => {
    adapter.mockImplementation(apiResponse({ code: 419001, messageKey: 'error.storageQuotaExceeded', messageParams: {} }))

    await expect(api.get('/files')).rejects.toMatchObject({
      response: { data: { code: 419001, messageKey: 'error.storageQuotaExceeded' } },
    })
    expect(toastError).toHaveBeenCalledWith('存储空间不足')
  })

  it('does not show a toast when suppressToast is set', async () => {
    adapter.mockImplementation(apiResponse({ code: 419007, messageKey: 'error.chunkMd5Mismatch', messageParams: {} }))

    await expect(api.post('/upload/session/chunk', {}, { suppressToast: true })).rejects.toBeTruthy()
    expect(toastError).not.toHaveBeenCalled()
  })

  it('refreshes token on 401001 and retries the original request', async () => {
    localStorage.setItem('refreshToken', 'old-refresh')

    adapter
      .mockImplementationOnce(apiResponse({ code: 401001, messageKey: 'error.tokenExpired', messageParams: {} }))
      .mockImplementationOnce(apiResponse({ code: 200, messageKey: 'common.success', messageParams: {}, data: { ok: true } }))

    axiosPost.mockResolvedValue({
      data: {
        code: 200,
        messageKey: 'common.success', messageParams: {},
        data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
      },
    })

    const res = await api.get('/files')

    expect(axiosPost).toHaveBeenCalledWith('/api/v1/auth/refresh', { refreshToken: 'old-refresh' }, { timeout: 30000 })
    expect(localStorage.getItem('accessToken')).toBe('new-access')
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh')
    expect(res.data.data).toEqual({ ok: true })
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it('uses one refresh request for concurrent 401001 responses', async () => {
    localStorage.setItem('refreshToken', 'old-refresh')

    adapter
      .mockImplementationOnce(apiResponse({ code: 401001, messageKey: 'error.tokenExpired', messageParams: {} }))
      .mockImplementationOnce(apiResponse({ code: 401001, messageKey: 'error.tokenExpired', messageParams: {} }))
      .mockImplementationOnce(apiResponse({ code: 200, messageKey: 'common.success', messageParams: {}, data: { id: 1 } }))
      .mockImplementationOnce(apiResponse({ code: 200, messageKey: 'common.success', messageParams: {}, data: { id: 2 } }))

    let resolveRefresh: (value: unknown) => void
    axiosPost.mockReturnValue(new Promise(resolve => {
      resolveRefresh = resolve
    }) as ReturnType<typeof axios.post>)

    const req1 = api.get('/files/1')
    const req2 = api.get('/files/2')

    await vi.waitFor(() => expect(axiosPost).toHaveBeenCalledTimes(1))
    expect(axiosPost).toHaveBeenCalledTimes(1)

    resolveRefresh!({
      data: {
        code: 200,
        messageKey: 'common.success', messageParams: {},
        data: { accessToken: 'queued-access', refreshToken: 'queued-refresh' },
      },
    })

    const results = await Promise.all([req1, req2])
    expect(results.map(res => res.data.data)).toEqual(expect.arrayContaining([{ id: 1 }, { id: 2 }]))
    expect(axiosPost).toHaveBeenCalledTimes(1)
  })

  it('refreshes when token validation returns a plain HTTP 401', async () => {
    localStorage.setItem('accessToken', 'expired-access')
    localStorage.setItem('refreshToken', 'old-refresh')
    adapter.mockImplementationOnce(apiHttpError(401)).mockImplementationOnce(apiResponse({ code: 200, data: { id: 7 } }))
    axiosPost.mockResolvedValue({
      data: {
        code: 200,
        messageKey: 'common.success', messageParams: {},
        data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
      },
    })

    await expect(ensureFreshAccessToken()).resolves.toBe('new-access')

    expect(axiosPost).toHaveBeenCalledWith('/api/v1/auth/refresh', { refreshToken: 'old-refresh' }, { timeout: 30000 })
    expect(localStorage.getItem('accessToken')).toBe('new-access')
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh')
    expect(toastError).not.toHaveBeenCalled()
  })

  it('uses the hash login route when a desktop refresh token is missing', async () => {
    isDesktopRuntime.mockReturnValue(true)
    window.__BCD_RUNTIME__ = { kind: 'qt', apiBaseUrl: '/api/v1', webBaseUrl: 'http://localhost' }

    await expect(refreshAccessToken()).rejects.toBeTruthy()

    expect(window.location.hash).toBe('#/login')
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
  })

  it('returns a revoked desktop session to login without an auth toast', async () => {
    isDesktopRuntime.mockReturnValue(true)
    window.__BCD_RUNTIME__ = { kind: 'qt', apiBaseUrl: '/api/v1', webBaseUrl: 'http://localhost' }
    localStorage.setItem('accessToken', 'revoked-access')
    localStorage.setItem('refreshToken', 'refresh')
    adapter.mockImplementation(apiResponse({ code: 401002, messageKey: 'error.tokenBlacklisted', messageParams: {} }))

    await expect(api.get('/files')).rejects.toBeTruthy()

    expect(window.location.hash).toBe('#/login')
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
    expect(toastError).not.toHaveBeenCalled()
  })
  it('rejects a late Web refresh without restoring the old login', async () => {
    let finish!: (value: any) => void
    localStorage.setItem('refreshToken', 'old-refresh')
    axiosPost.mockReturnValueOnce(new Promise(resolve => { finish = resolve }) as never)
    const refreshing = refreshAccessToken()
    const rejected = expect(refreshing).rejects.toThrow('SESSION_CHANGED')
    await vi.waitFor(() => expect(axiosPost).toHaveBeenCalledTimes(1))
    invalidateIdentity('logout')
    localStorage.setItem('accessToken', 'next-access')
    localStorage.setItem('refreshToken', 'next-refresh')
    finish({ data: { code: 200, data: { accessToken: 'old-access', refreshToken: 'old-refreshed' } } })
    await rejected
    expect(localStorage.getItem('accessToken')).toBe('next-access')
    expect(localStorage.getItem('refreshToken')).toBe('next-refresh')
  })
  it('never refreshes more than once when the retried request is still unauthorized', async () => {
    localStorage.setItem('refreshToken', 'old-refresh')
    adapter.mockImplementation(apiHttpError(401))
    axiosPost.mockResolvedValue({ data: { code: 200, data: { accessToken: 'new-access', refreshToken: 'new-refresh' } } })
    await expect(api.get('/files')).rejects.toBeTruthy()
    expect(axiosPost).toHaveBeenCalledTimes(1)
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

})
