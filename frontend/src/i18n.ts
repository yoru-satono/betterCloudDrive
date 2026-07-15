import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'
import { ref } from 'vue'

export type SupportedLocale = 'zh-CN' | 'en-US'
export const SUPPORTED_LOCALES: SupportedLocale[] = ['zh-CN', 'en-US']
const STORAGE_KEY = 'preferredLocale'

function initialLocale(): SupportedLocale {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (SUPPORTED_LOCALES.includes(stored as SupportedLocale)) return stored as SupportedLocale
  if (import.meta.env.MODE === 'test') return 'zh-CN'
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

export type MessageParams = Record<string, string | number | boolean | null>
export const locale = ref<SupportedLocale>(initialLocale())
export const i18n = createI18n({ legacy: false, locale: locale.value, fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN, 'en-US': enUS } })

export function setLocale(next: SupportedLocale) {
  locale.value = next
  i18n.global.locale.value = next
  localStorage.setItem(STORAGE_KEY, next)
  document.documentElement.lang = next
}

setLocale(locale.value)

export function resolveMessage(key: string | null | undefined, params: MessageParams = {}): string {
  if (key && i18n.global.te(key, locale.value)) return i18n.global.t(key, params) as string
  console.warn('[i18n] Unknown server message key', key)
  if (import.meta.env.DEV && key) return locale.value === 'zh-CN' ? `操作失败（${key}）` : `Operation failed (${key})`
  return locale.value === 'zh-CN' ? '操作失败，请稍后重试' : 'Operation failed. Please try again later'
}
