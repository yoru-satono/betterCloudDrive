import { invoke } from '@tauri-apps/api/core'
import { isDesktopRuntime } from '@/config/runtime'

export type ProxyMode = 'system' | 'manual' | 'disabled'

export interface DesktopSettings {
  uploadLimitBytesPerSec: number | null
  downloadLimitBytesPerSec: number | null
  maxConcurrentUploads: number
  maxConcurrentDownloads: number
  defaultDownloadDir: string | null
  proxyMode: ProxyMode
  proxyUrl: string
  proxyUsername: string
  proxyPassword: string
}

export const DEFAULT_DESKTOP_SETTINGS: DesktopSettings = {
  uploadLimitBytesPerSec: null,
  downloadLimitBytesPerSec: null,
  maxConcurrentUploads: 3,
  maxConcurrentDownloads: 3,
  defaultDownloadDir: null,
  proxyMode: 'system',
  proxyUrl: '',
  proxyUsername: '',
  proxyPassword: '',
}

export const isDesktopSettingsRuntime = () => isDesktopRuntime()

export async function getDesktopSettings() {
  if (!isDesktopSettingsRuntime()) return { ...DEFAULT_DESKTOP_SETTINGS }
  return invoke<DesktopSettings>('get_desktop_settings')
}

export async function saveDesktopSettings(settings: DesktopSettings) {
  if (!isDesktopSettingsRuntime()) return settings
  return invoke<DesktopSettings>('save_desktop_settings', { settings })
}

export async function chooseDefaultDownloadDirectory() {
  if (!isDesktopSettingsRuntime()) return null
  return invoke<string | null>('choose_default_download_directory')
}

export function bytesToMbps(value: number | null | undefined) {
  if (!value) return ''
  return trimNumber(value / 1024 / 1024)
}

export function mbpsToBytes(value: string) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) return null
  return Math.round(parsed * 1024 * 1024)
}

function trimNumber(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/\.?0+$/, '')
}
