import { invoke, rawInvoke, listen } from './bridge'
import { getApiBaseUrl } from './runtime'
import { refreshAccessToken } from './client'
import { assertIdentity, identityRevision, invalidateIdentity } from './identity'

let synced: { revision: number; token: string; base: string } | undefined
let pending: { key: string; promise: Promise<void> } | undefined
let refresh: { revision: number; promise: Promise<void> } | undefined
window.addEventListener('bcd:identity-invalidated', () => { synced = undefined; pending = undefined })
export function synchronizeSession(): Promise<void> {
  const revision = identityRevision()
  const token = localStorage.getItem('accessToken') || ''
  const base = getApiBaseUrl()
  if (!token) return Promise.reject(new Error('AUTH_REQUIRED'))
  if (synced?.revision === revision && synced.token === token && synced.base === base) return Promise.resolve()
  const key = JSON.stringify([revision, token, base])
  if (pending?.key === key) return pending.promise
  const entry = { key, promise: undefined as unknown as Promise<void> }
  entry.promise = (async () => {
    try {
      const guard = () => {
        assertIdentity(revision)
        if (token !== localStorage.getItem('accessToken') || base !== getApiBaseUrl()) throw new Error('SESSION_CHANGED')
      }
      const result = await invoke<{ authenticated: boolean }>('session:sync', { token, apiBaseUrl: base }, guard)
      assertIdentity(revision)
      if (result.authenticated !== true) throw new Error('NATIVE_SYNC_FAILED')
      if (token !== localStorage.getItem('accessToken') || base !== getApiBaseUrl()) throw new Error('SESSION_CHANGED')
      synced = { revision, token, base }
    } finally {
      if (pending === entry) pending = undefined
    }
  })()
  pending = entry
  return entry.promise
}
export function clearSession(reason = 'logout') {
  invalidateIdentity(reason)
  return rawInvoke('session:clear')
}
void listen('session:refresh-required', () => {
  const revision = identityRevision()
  if (refresh?.revision === revision) return
  const entry = { revision, promise: undefined as unknown as Promise<void> }
  entry.promise = (async () => {
    if (!localStorage.getItem('accessToken')) throw new Error('AUTH_REQUIRED')
    if (localStorage.getItem('accessToken') === synced?.token) await refreshAccessToken()
    assertIdentity(revision)
    await synchronizeSession()
  })().catch(async () => {
    if (revision === identityRevision()) {
      const clearing = clearSession('sync-failed')
      window.location.hash = '#/login'
      await clearing.catch(() => undefined)
    }
  }).finally(() => { if (refresh === entry) refresh = undefined })
  refresh = entry
})
