const API_BASE_URL_STORAGE_KEY = 'bcd.apiBaseUrl'
const WEB_BASE_URL_STORAGE_KEY = 'bcd.webBaseUrl'

const WEB_API_BASE_URL = '/api/v1'
const DESKTOP_API_BASE_URL = import.meta.env.VITE_DESKTOP_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'
const DESKTOP_WEB_BASE_URL = import.meta.env.VITE_DESKTOP_WEB_BASE_URL || 'http://127.0.0.1:3000'

type RuntimeWindow = Window & {
  __TAURI__?: unknown
  __TAURI_INTERNALS__?: unknown
}

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

export const isDesktopRuntime = () => {
  const env = import.meta.env as Record<string, string | undefined>
  const win = window as RuntimeWindow
  return !!env.TAURI_PLATFORM || !!win.__TAURI__ || !!win.__TAURI_INTERNALS__
}

export const normalizeBaseUrl = (value: string) => value.trim().replace(/\/+$/, '')

export const getApiBaseUrl = () => {
  const stored = storageGet(API_BASE_URL_STORAGE_KEY)
  if (stored) return normalizeBaseUrl(stored)

  const env = import.meta.env.VITE_API_BASE_URL
  if (env) return normalizeBaseUrl(env)

  return isDesktopRuntime() ? DESKTOP_API_BASE_URL : WEB_API_BASE_URL
}

export const setStoredApiBaseUrl = (value: string) => {
  storageSet(API_BASE_URL_STORAGE_KEY, normalizeBaseUrl(value))
}

export const getWebBaseUrl = () => {
  const stored = storageGet(WEB_BASE_URL_STORAGE_KEY)
  if (stored) return normalizeBaseUrl(stored)

  const env = import.meta.env.VITE_WEB_BASE_URL
  if (env) return normalizeBaseUrl(env)

  return isDesktopRuntime() ? DESKTOP_WEB_BASE_URL : window.location.origin
}

export const setStoredWebBaseUrl = (value: string) => {
  storageSet(WEB_BASE_URL_STORAGE_KEY, normalizeBaseUrl(value))
}

export const buildShareUrl = (shareCode: string) => `${getWebBaseUrl()}/s/${shareCode}`
