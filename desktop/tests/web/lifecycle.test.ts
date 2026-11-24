import { beforeEach, expect, it, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import axios from 'axios'
const bridge = vi.hoisted(() => ({
  invoke: vi.fn(), rawInvoke: vi.fn(), waitForConnection: vi.fn(() => Promise.resolve()),
  listen: vi.fn(() => Promise.resolve(() => {})),
  configureConnection: vi.fn(), isConnectionConfirmed: vi.fn(() => true),
}))
vi.mock('@/platform/desktop', () => bridge)
vi.mock('../../../frontend/src/stores/upload', () => ({ useUploadStore: () => ({ queue: [] }) }))
vi.mock('../../../frontend/src/stores/transfer', () => ({ useTransferStore: () => ({ applyQueue: vi.fn() }) }))
import api, { refreshAccessToken } from '@/api/client'
import { invalidateIdentity } from '@/platform/identity'
import { synchronizeSession, clearSession } from '@/platform/session'
import { getApiBaseUrl, setStoredApiBaseUrl } from '@/config/runtime'
import { useAuthStore } from '@/stores/auth'
const deferred = <T = any>() => { let resolve!: (v: T) => void, reject!: (e: Error) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const tick = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
const tokens = (prefix = 'synthetic') => {
  localStorage.setItem('accessToken', prefix + '-access')
  localStorage.setItem('refreshToken', prefix + '-refresh')
}
beforeEach(() => {
  invalidateIdentity('test')
  setActivePinia(createPinia())
  bridge.invoke.mockReset().mockResolvedValue({ authenticated: true })
  bridge.rawInvoke.mockReset().mockResolvedValue({ authenticated: false })
  bridge.configureConnection.mockReset().mockImplementation(async value => ({ apiBaseUrl: value }))
  vi.restoreAllMocks()
})
it('commits the address only after native acknowledgement and clears identity first', async () => {
  tokens()
  const previous = getApiBaseUrl(), ack = deferred()
  bridge.configureConnection.mockReturnValueOnce(ack.promise)
  const saving = setStoredApiBaseUrl('https://next.example/api/v1/')
  await tick()
  expect(localStorage.getItem('accessToken')).toBeNull()
  expect(bridge.rawInvoke).toHaveBeenCalledWith('session:clear')
  expect(getApiBaseUrl()).toBe(previous)
  ack.resolve({ apiBaseUrl: 'https://next.example/api/v1' })
  await saving
  expect(getApiBaseUrl()).toBe('https://next.example/api/v1')
})
it('keeps the previous address on failed acknowledgement and supports retry without old tokens', async () => {
  tokens()
  const previous = getApiBaseUrl()
  bridge.configureConnection.mockRejectedValueOnce(new Error('COMMAND_FAILED'))
  await expect(setStoredApiBaseUrl('https://retry.example/api/v1')).rejects.toThrow('COMMAND_FAILED')
  expect(getApiBaseUrl()).toBe(previous)
  expect(localStorage.getItem('refreshToken')).toBeNull()
  await setStoredApiBaseUrl('https://retry.example/api/v1')
  expect(getApiBaseUrl()).toBe('https://retry.example/api/v1')
})
it('rejects invalid addresses before touching the identity', async () => {
  tokens()
  await expect(setStoredApiBaseUrl('file:///private')).rejects.toThrow('INVALID_API_BASE')
  expect(localStorage.getItem('accessToken')).toBe('synthetic-access')
  expect(bridge.configureConnection).not.toHaveBeenCalled()
})
it('deduplicates concurrent native synchronization and does not cache a failed sync', async () => {
  tokens()
  const ack = deferred()
  bridge.invoke.mockReturnValueOnce(ack.promise)
  const first = synchronizeSession(), second = synchronizeSession()
  expect(first).toBe(second)
  ack.reject(new Error('OFFLINE'))
  await expect(first).rejects.toThrow('OFFLINE')
  await synchronizeSession()
  await synchronizeSession()
  expect(bridge.invoke).toHaveBeenCalledTimes(2)
})
it('ignores a native acknowledgement returned after logout', async () => {
  tokens()
  const ack = deferred()
  bridge.invoke.mockReturnValueOnce(ack.promise)
  const syncing = synchronizeSession()
  const outcome = expect(syncing).rejects.toThrow('SESSION_CHANGED')
  await clearSession()
  ack.resolve({ authenticated: true })
  await outcome
  tokens('new')
  await synchronizeSession()
  expect(bridge.invoke).toHaveBeenCalledTimes(2)
})
it('deduplicates refreshes for the current identity', async () => {
  tokens()
  const result = deferred()
  vi.spyOn(axios, 'post').mockReturnValueOnce(result.promise)
  const first = refreshAccessToken(), second = refreshAccessToken()
  expect(first).toBe(second)
  await tick()
  result.resolve({ data: { code: 200, data: { accessToken: 'fresh-access', refreshToken: 'fresh-refresh' } } })
  await first
  expect(localStorage.getItem('accessToken')).toBe('fresh-access')
})
it.each(['success', 'failure'])('late refresh %s cannot restore or clear a later login', async outcome => {
  tokens('old')
  const old = deferred(), fresh = deferred()
  vi.spyOn(axios, 'post').mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
  const refreshing = refreshAccessToken()
  const rejected = expect(refreshing).rejects.toThrow('SESSION_CHANGED')
  await tick()
  await clearSession()
  tokens('new')
  const newRefresh = refreshAccessToken()
  await tick()
  if (outcome === 'success') old.resolve({ data: { code: 200, data: { accessToken: 'obsolete', refreshToken: 'obsolete' } } })
  else old.reject(new Error('OLD_NETWORK_FAILURE'))
  await rejected
  expect(localStorage.getItem('accessToken')).toBe('new-access')
  expect(refreshAccessToken()).toBe(newRefresh)
  fresh.resolve({ data: { code: 200, data: { accessToken: 'newest-access', refreshToken: 'newest-refresh' } } })
  await newRefresh
  expect(localStorage.getItem('accessToken')).toBe('newest-access')
})
it('server switching invalidates an in-flight refresh before accepting the new URL', async () => {
  tokens()
  const result = deferred()
  vi.spyOn(axios, 'post').mockReturnValueOnce(result.promise)
  const refreshing = refreshAccessToken()
  const rejected = expect(refreshing).rejects.toThrow('SESSION_CHANGED')
  await tick()
  await setStoredApiBaseUrl('https://switched.example/api/v1')
  result.resolve({ data: { code: 200, data: { accessToken: 'old-access', refreshToken: 'old-refresh' } } })
  await rejected
  expect(localStorage.getItem('accessToken')).toBeNull()
})
it('a stale API response is rejected before callers can apply its result', async () => {
  tokens()
  const result = deferred()
  api.defaults.adapter = config => result.promise.then(data => ({ data, status: 200, statusText: 'OK', headers: {}, config }))
  const request = api.get('/auth/me')
  const rejected = expect(request).rejects.toThrow('SESSION_CHANGED')
  await tick()
  await clearSession()
  result.resolve({ code: 200, data: { id: 1 } })
  await rejected
})
it('failed native startup recovery stays unauthenticated and permits another login', async () => {
  tokens()
  api.defaults.adapter = async config => ({ data: { code: 200, data: config.url === '/auth/login' ? { accessToken: 'relogin-access', refreshToken: 'relogin-refresh' } : { id: 1, preferredLocale: 'en-US' } }, status: 200, statusText: 'OK', headers: {}, config })
  bridge.invoke.mockRejectedValueOnce(new Error('NATIVE_SYNC_FAILED'))
  const auth = useAuthStore()
  expect(await auth.initializeSession()).toBe(false)
  expect(auth.user).toBeNull()
  expect(localStorage.getItem('accessToken')).toBeNull()
  await auth.login('synthetic-user', 'synthetic-password')
  expect(auth.user?.id).toBe(1)
  expect(localStorage.getItem('accessToken')).toBe('relogin-access')
})
it('logout clears web identity immediately even when native cleanup fails', async () => {
  tokens()
  const auth = useAuthStore()
  bridge.rawInvoke.mockRejectedValueOnce(new Error('BRIDGE_FAILED'))
  vi.spyOn(axios, 'post').mockResolvedValue({})
  const logout = auth.logout()
  expect(localStorage.getItem('accessToken')).toBeNull()
  expect(auth.user).toBeNull()
  expect(window.location.hash).toBe('#/login')
  await logout
  expect(axios.post).toHaveBeenCalledWith(expect.stringContaining('/auth/logout'), undefined, expect.objectContaining({ headers: { Authorization: 'Bearer synthetic-access' } }))
})
it('retries a plain HTTP 401 once through the guarded refresh path', async () => {
  tokens()
  let calls = 0
  api.defaults.adapter = async config => {
    calls++
    if (!config._retry) throw new axios.AxiosError('Unauthorized', undefined, config, undefined, { data: '', status: 401, statusText: 'Unauthorized', headers: {}, config })
    return { data: { code: 200, data: { id: 1 } }, status: 200, statusText: 'OK', headers: {}, config }
  }
  vi.spyOn(axios, 'post').mockResolvedValueOnce({ data: { code: 200, data: { accessToken: 'fresh-access', refreshToken: 'fresh-refresh' } } })
  await api.get('/auth/me')
  expect(calls).toBe(2)
  expect(axios.post).toHaveBeenCalledTimes(1)
})
it('an older login cannot install tokens after a second login has started', async () => {
  const old = deferred()
  api.defaults.adapter = config => config.url === '/auth/login' && JSON.parse(config.data).username === 'old'
    ? old.promise.then(data => ({ data, status: 200, statusText: 'OK', headers: {}, config }))
    : Promise.resolve({ data: { code: 200, data: config.url === '/auth/login' ? { accessToken: 'new-access', refreshToken: 'new-refresh' } : { id: 2 } }, status: 200, statusText: 'OK', headers: {}, config })
  const auth = useAuthStore()
  const first = auth.login('old', 'synthetic-password')
  const rejected = expect(first).rejects.toThrow('SESSION_CHANGED')
  await tick()
  await auth.login('new', 'synthetic-password')
  old.resolve({ code: 200, data: { accessToken: 'obsolete-access', refreshToken: 'obsolete-refresh' } })
  await rejected
  expect(auth.user?.id).toBe(2)
  expect(localStorage.getItem('accessToken')).toBe('new-access')
})
it('captures API identity synchronously before logout in the same event turn', async () => {
  tokens()
  const result = deferred()
  api.defaults.adapter = config => result.promise.then(data => ({ data, status: 200, statusText: 'OK', headers: {}, config }))
  const request = api.get('/auth/me')
  const rejected = expect(request).rejects.toThrow('SESSION_CHANGED')
  await clearSession()
  tokens('new')
  result.resolve({ code: 200, data: { id: 1 } })
  await rejected
})
it('out-of-order native synchronization cannot replace a newer confirmed identity', async () => {
  tokens('old')
  const old = deferred()
  bridge.invoke.mockReturnValueOnce(old.promise)
  const first = synchronizeSession()
  const rejected = expect(first).rejects.toThrow('SESSION_CHANGED')
  tokens('new')
  await synchronizeSession()
  old.resolve({ authenticated: true })
  await rejected
  await synchronizeSession()
  expect(bridge.invoke).toHaveBeenCalledTimes(2)
})
