import { createI18n } from 'vue-i18n'
import { ref } from 'vue'

export type SupportedLocale = string
const STORAGE_KEY = 'preferredLocale'
const DEFAULT_LOCALE: SupportedLocale = 'en-US'
const FALLBACK_LOCALE: SupportedLocale = 'zh-Hans'
const localeModules = import.meta.glob<{ default: Record<string, unknown> }>('./locales/*.ts')
const localeLoaders = Object.fromEntries(
  Object.entries(localeModules).map(([path, loader]) => {
    const localeName = path.match(/\/([^/]+)\.ts$/)?.[1]
    if (!localeName) throw new Error(`Invalid locale module path: ${path}`)
    const canonical = Intl.getCanonicalLocales(localeName)[0]
    if (canonical !== localeName) throw new Error(`Locale filename must be canonical: ${localeName}`)
    return [canonical, loader]
  }),
) as Record<SupportedLocale, () => Promise<{ default: Record<string, unknown> }>>
export const SUPPORTED_LOCALES = Object.freeze(Object.keys(localeLoaders))
if (!SUPPORTED_LOCALES.includes(DEFAULT_LOCALE)) {
  throw new Error(`Default locale module is missing: ${DEFAULT_LOCALE}`)
}
if (!SUPPORTED_LOCALES.includes(FALLBACK_LOCALE)) {
  throw new Error(`Fallback locale module is missing: ${FALLBACK_LOCALE}`)
}
const loadingLocales = new Map<SupportedLocale, Promise<void>>()
let switchQueue = Promise.resolve()
let pendingSwitches = 0

export function resolveSupportedLocale(value: string | null | undefined): SupportedLocale | undefined {
  if (!value) return undefined
  let canonical: string
  try {
    canonical = Intl.getCanonicalLocales(value)[0]
  } catch {
    return undefined
  }
  if (SUPPORTED_LOCALES.includes(canonical)) return canonical
  const language = canonical.split('-')[0]
  return SUPPORTED_LOCALES.find(candidate => candidate.split('-')[0] === language)
}

function initialLocale(): SupportedLocale {
  const stored = resolveSupportedLocale(localStorage.getItem(STORAGE_KEY))
  if (stored) return stored
  if (import.meta.env.MODE === 'test') return FALLBACK_LOCALE
  return resolveSupportedLocale(navigator.language) || DEFAULT_LOCALE
}

export type MessageParams = Record<string, string | number | boolean | null>
export const locale = ref<SupportedLocale>(initialLocale())
export const localeLoading = ref(false)
export const i18n = createI18n({
  legacy: false,
  locale: locale.value,
  // Never leak copy from a different language. Locale completeness is asserted in tests.
  fallbackLocale: false,
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV,
  messages: {},
  missing: (currentLocale, key) => currentLocale === 'zh-Hans' ? `暂缺文本（${key}）` : `Translation unavailable (${key})`,
})

async function loadLocale(next: SupportedLocale): Promise<void> {
  if (i18n.global.availableLocales.includes(next)) return
  const existing = loadingLocales.get(next)
  if (existing) return existing

  const loader = localeLoaders[next]
  if (!loader) throw new Error(`Unsupported locale: ${next}`)
  const loading = loader()
    .then(module => {
      i18n.global.setLocaleMessage(next, module.default)
    })
    .finally(() => {
      loadingLocales.delete(next)
    })
  loadingLocales.set(next, loading)
  return loading
}

function applyLocale(next: SupportedLocale) {
  locale.value = next
  i18n.global.locale.value = next
  localStorage.setItem(STORAGE_KEY, next)
  document.documentElement.lang = next
}

export function setLocale(next: SupportedLocale): Promise<void> {
  pendingSwitches++
  localeLoading.value = true
  const operation = switchQueue.then(async () => {
    await loadLocale(next)
    applyLocale(next)
  })
  switchQueue = operation.catch(() => undefined)
  return operation.finally(() => {
    pendingSwitches--
    localeLoading.value = pendingSwitches > 0
  })
}

export async function initializeLocale(): Promise<void> {
  const selected = initialLocale()
  try {
    await setLocale(selected)
  } catch (error) {
    if (selected === FALLBACK_LOCALE) throw error
    await setLocale(FALLBACK_LOCALE)
  }
}

export function resolveMessage(key: string | null | undefined, params: MessageParams = {}): string {
  if (key && i18n.global.te(key, locale.value)) return i18n.global.t(key, params) as string
  console.warn('[i18n] Unknown server message key', key)
  if (import.meta.env.DEV && key) return locale.value === 'zh-Hans' ? `操作失败（${key}）` : `Operation failed (${key})`
  return locale.value === 'zh-Hans' ? '操作失败，请稍后重试' : 'Operation failed. Please try again later'
}
