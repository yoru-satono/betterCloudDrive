const API_BASE_URL_STORAGE_KEY = 'bcd.apiBaseUrl'
const WEB_BASE_URL_STORAGE_KEY = 'bcd.webBaseUrl'

const WEB_API_BASE_URL = '/api/v1'


const storageGet = (key: string) => {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

const storageSet = (key: string, value: string) => {
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
  } catch {
    // ignore unavailable storage
  }
}

export const isDesktopRuntime = () => false

export const normalizeBaseUrl = (value: string) => value.trim().replace(/\/+$/, '')

export const getApiBaseUrl = () => {
  const stored = storageGet(API_BASE_URL_STORAGE_KEY)
  if (stored) return normalizeBaseUrl(stored)

  const env = import.meta.env.VITE_API_BASE_URL
  if (env) return normalizeBaseUrl(env)

  return WEB_API_BASE_URL
}

export const setStoredApiBaseUrl = (value: string) => {
  storageSet(API_BASE_URL_STORAGE_KEY, normalizeBaseUrl(value))
}

export const getWebBaseUrl = () => {
  const stored = storageGet(WEB_BASE_URL_STORAGE_KEY)
  if (stored) return normalizeBaseUrl(stored)

  const env = import.meta.env.VITE_WEB_BASE_URL
  if (env) return normalizeBaseUrl(env)

  return window.location.origin
}

export const setStoredWebBaseUrl = (value: string) => {
  storageSet(WEB_BASE_URL_STORAGE_KEY, normalizeBaseUrl(value))
}

export const buildShareUrl = (shareCode: string) => `${getWebBaseUrl()}/s/${shareCode}`

export const resolveShareUrl = (share: { shareCode: string; shareUrl?: string | null }) =>
  share.shareUrl || buildShareUrl(share.shareCode)
