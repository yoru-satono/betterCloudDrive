import { beforeEach, expect, it, vi } from 'vitest'
import { getApiBaseUrl, getWebBaseUrl, setStoredApiBaseUrl } from '@/config/runtime'
import { identityRevision } from '@/platform/identity'
const native = vi.hoisted(() => ({ configureConnection: vi.fn(), rawInvoke: vi.fn() }))
vi.mock('@/platform/desktop', () => native)
beforeEach(() => { delete window.__BCD_RUNTIME__; localStorage.clear(); vi.unstubAllEnvs() })
it('uses Web defaults without consulting Qt configuration', () => {
  vi.stubEnv('VITE_API_BASE_URL', 'https://web-api.example/api/v1')
  expect(getApiBaseUrl()).toBe('https://web-api.example/api/v1')
  localStorage.setItem('bcd.apiBaseUrl', '/custom/api/v1')
  expect(getApiBaseUrl()).toBe('/custom/api/v1')
})
it('uses Qt startup defaults regardless of Web build environment and keeps saved addresses', () => {
  window.__BCD_RUNTIME__ = { kind: 'qt', apiBaseUrl: 'https://qt.example/api/v1', webBaseUrl: 'https://share.example' }
  vi.stubEnv('VITE_API_BASE_URL', 'https://wrong.example/api/v1')
  vi.stubEnv('VITE_WEB_BASE_URL', 'https://wrong.example')
  expect(getApiBaseUrl()).toBe('https://qt.example/api/v1')
  expect(getWebBaseUrl()).toBe('https://share.example')
  localStorage.setItem('bcd.apiBaseUrl', 'https://saved.example/api/v1')
  expect(getApiBaseUrl()).toBe('https://saved.example/api/v1')
})
it('invalidates Web identity when switching servers without any native request', async () => {
  window.history.replaceState({}, '', '/login')
  localStorage.setItem('accessToken', 'old-access')
  const revision = identityRevision()
  await setStoredApiBaseUrl('https://next.example/api/v1')
  expect(identityRevision()).toBeGreaterThan(revision)
  expect(localStorage.getItem('accessToken')).toBeNull()
  expect(getApiBaseUrl()).toBe('https://next.example/api/v1')
  expect(native.configureConnection).not.toHaveBeenCalled()
  expect(native.rawInvoke).not.toHaveBeenCalled()
})
