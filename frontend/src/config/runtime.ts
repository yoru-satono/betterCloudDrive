import { isQtRuntime, runtimeDefaults, redirectToLogin } from '@/platform/environment'
import { configureConnection, rawInvoke } from '@/platform/desktop'
import { invalidateIdentity } from '@/platform/identity'
const API_KEY = 'bcd.apiBaseUrl', WEB_KEY = 'bcd.webBaseUrl'
export const normalizeBaseUrl = (value: string) => value.trim().replace(/\/+$/, '')
export const isDesktopRuntime = isQtRuntime
const stored = (key: string) => { try { return localStorage.getItem(key) } catch { return null } }
export const getApiBaseUrl = () => normalizeBaseUrl(stored(API_KEY) || (isQtRuntime()
  ? runtimeDefaults()!.apiBaseUrl : import.meta.env.VITE_API_BASE_URL || '/api/v1'))
export const getWebBaseUrl = () => normalizeBaseUrl(stored(WEB_KEY) || (isQtRuntime()
  ? runtimeDefaults()!.webBaseUrl : import.meta.env.VITE_WEB_BASE_URL || window.location.origin))
let saving: Promise<void> = Promise.resolve()
export function setStoredApiBaseUrl(value: string): Promise<void> {
  value = normalizeBaseUrl(value)
  saving = saving.catch(() => undefined).then(async () => {
    let parsed: URL
    try { parsed = new URL(value, window.location.origin) } catch { throw new Error('INVALID_API_BASE') }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash
      || (isQtRuntime() && (!/^https?:\/\//i.test(value) || !parsed.pathname.endsWith('/api/v1')))) throw new Error('INVALID_API_BASE')
    if (!isQtRuntime() && value === getApiBaseUrl()) return
    invalidateIdentity('connection')
    redirectToLogin()
    if (isQtRuntime()) {
      await rawInvoke('session:clear')
      await configureConnection(value)
    }
    localStorage.setItem(API_KEY, value)
    window.dispatchEvent(new Event('bcd:connection-changed'))
  })
  return saving
}
export const setStoredWebBaseUrl = (value: string) => localStorage.setItem(WEB_KEY, normalizeBaseUrl(value))
export const buildShareUrl = (code: string) => getWebBaseUrl() + '/s/' + code
export const resolveShareUrl = (share: { shareCode: string; shareUrl?: string | null }) => share.shareUrl || buildShareUrl(share.shareCode)
