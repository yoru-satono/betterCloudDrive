import { configureConnection, isConnectionConfirmed, rawInvoke } from './bridge'
import { invalidateIdentity } from './identity'

const API_KEY = 'bcd.apiBaseUrl'
const WEB_KEY = 'bcd.webBaseUrl'
export const normalizeBaseUrl = (value: string) => value.trim().replace(/\/+$/, '')
export const isDesktopRuntime = () => true
export const getApiBaseUrl = () => normalizeBaseUrl(localStorage.getItem(API_KEY) || import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1')
export const getWebBaseUrl = () => normalizeBaseUrl(localStorage.getItem(WEB_KEY) || import.meta.env.VITE_WEB_BASE_URL || 'http://127.0.0.1:3000')
let saving: Promise<void> = Promise.resolve()
export function setStoredApiBaseUrl(value: string): Promise<void> {
  value = normalizeBaseUrl(value)
  saving = saving.catch(() => undefined).then(async () => {
    let parsed: URL
    try { parsed = new URL(value) } catch { throw new Error('INVALID_API_BASE') }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password ||
        parsed.search || parsed.hash || !parsed.pathname.endsWith('/api/v1')) throw new Error('INVALID_API_BASE')
    if (value === getApiBaseUrl() && isConnectionConfirmed()) return
    // Never restore credentials if saving fails or its response is lost.
    invalidateIdentity('connection')
    window.location.hash = '#/login'
    await rawInvoke('session:clear')
    await configureConnection(value)
    localStorage.setItem(API_KEY, value)
    window.dispatchEvent(new Event('bcd:connection-changed'))
  })
  return saving
}
export const setStoredWebBaseUrl = (value: string) => localStorage.setItem(WEB_KEY, normalizeBaseUrl(value))
export const buildShareUrl = (shareCode: string) => getWebBaseUrl() + '/s/' + shareCode
export const resolveShareUrl = (share: { shareCode: string; shareUrl?: string | null }) => share.shareUrl || buildShareUrl(share.shareCode)
