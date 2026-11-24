import { computed, readonly, ref, watch } from 'vue'
import { isDesktopRuntime } from '@/config/runtime'

export type ThemeMode = 'system' | 'light' | 'dark'
export type ResolvedTheme = Exclude<ThemeMode, 'system'>

export const THEME_STORAGE_KEY = 'bcd.themeMode'

const isThemeMode = (value: string | null): value is ThemeMode =>
  value === 'system' || value === 'light' || value === 'dark'

export const resolveTheme = (mode: ThemeMode, systemDark: boolean): ResolvedTheme =>
  mode === 'system' ? (systemDark ? 'dark' : 'light') : mode

function loadThemeMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    return isThemeMode(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

const mediaQuery = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null
const mode = ref<ThemeMode>(loadThemeMode())
const systemDark = ref(mediaQuery?.matches ?? false)
const resolved = computed(() => resolveTheme(mode.value, systemDark.value))
let initialized = false

async function syncDesktopTheme(nextMode: ThemeMode) {
  if (!isDesktopRuntime()) return
  try {
    const { setTheme } = await import('@/platform/desktop')
    await setTheme(nextMode === 'system' ? null : nextMode)
  } catch (error) {
    console.warn('Unable to synchronize the desktop theme.', error)
  }
}

function applyTheme(nextMode: ThemeMode, nextResolved: ResolvedTheme) {
  document.documentElement.dataset.theme = nextResolved
  document.documentElement.style.colorScheme = nextResolved
  void syncDesktopTheme(nextMode)
}

export function initializeTheme() {
  if (initialized) return
  initialized = true
  mediaQuery?.addEventListener('change', event => {
    systemDark.value = event.matches
  })
  watch([mode, resolved], ([nextMode, nextResolved]) => {
    applyTheme(nextMode, nextResolved)
  }, { immediate: true, flush: 'sync' })
}

export function setThemeMode(nextMode: ThemeMode) {
  mode.value = nextMode
  try {
    localStorage.setItem(THEME_STORAGE_KEY, nextMode)
  } catch {
    // Theme still applies for the current session when storage is unavailable.
  }
}

export const themeMode = readonly(mode)
export const resolvedTheme = readonly(resolved)
