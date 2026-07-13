import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'

export type MessageParams = Record<string, string | number | boolean | null>
export const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: false, messages: { 'zh-CN': zhCN } })

export function resolveMessage(key: string | null | undefined, params: MessageParams = {}): string {
  if (key && i18n.global.te(key, 'zh-CN')) return i18n.global.t(key, params) as string
  console.warn('[i18n] Unknown server message key', key)
  return import.meta.env.DEV && key ? `操作失败（${key}）` : '操作失败，请稍后重试'
}
