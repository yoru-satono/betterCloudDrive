import { beforeEach, describe, expect, it, vi } from 'vitest'

let systemDark = false
let mediaListener: ((event: MediaQueryListEvent) => void) | undefined

describe('theme preferences', () => {
  beforeEach(() => {
    vi.resetModules()
    systemDark = false
    mediaListener = undefined
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn(() => ({
        matches: systemDark,
        media: '(prefers-color-scheme: dark)',
        onchange: null,
        addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => { mediaListener = listener },
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    })
  })

  it('defaults to the current system theme', async () => {
    systemDark = true
    const theme = await import('@/theme')
    theme.initializeTheme()

    expect(theme.themeMode.value).toBe('system')
    expect(theme.resolvedTheme.value).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('persists an explicit selection and applies it immediately', async () => {
    const theme = await import('@/theme')
    theme.initializeTheme()
    theme.setThemeMode('dark')

    expect(localStorage.getItem(theme.THEME_STORAGE_KEY)).toBe('dark')
    expect(theme.resolvedTheme.value).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('reacts to system changes only while in system mode', async () => {
    const theme = await import('@/theme')
    theme.initializeTheme()
    mediaListener?.({ matches: true } as MediaQueryListEvent)
    expect(theme.resolvedTheme.value).toBe('dark')

    theme.setThemeMode('light')
    mediaListener?.({ matches: false } as MediaQueryListEvent)
    expect(theme.resolvedTheme.value).toBe('light')
  })

  it('falls back to system for an invalid stored value', async () => {
    localStorage.setItem('bcd.themeMode', 'sepia')
    const theme = await import('@/theme')
    expect(theme.themeMode.value).toBe('system')
  })
})
