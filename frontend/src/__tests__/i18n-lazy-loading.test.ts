import { afterEach, describe, expect, it, vi } from 'vitest'

describe('lazy locale loading', () => {
  afterEach(() => {
    vi.doUnmock('@/locales/en-US')
    vi.resetModules()
  })

  it('registers only the initial locale until another locale is requested', async () => {
    vi.resetModules()
    const { i18n, initializeLocale, resolveSupportedLocale, setLocale, SUPPORTED_LOCALES } = await import('@/i18n')

    await initializeLocale()
    expect([...SUPPORTED_LOCALES].sort()).toEqual(['en-US', 'zh-CN'])
    expect(resolveSupportedLocale('en-us')).toBe('en-US')
    expect(resolveSupportedLocale('zh-Hant')).toBe('zh-CN')
    expect(resolveSupportedLocale('fr-FR')).toBeUndefined()
    expect(i18n.global.availableLocales).toEqual(['zh-CN'])

    await setLocale('en-US')
    expect(i18n.global.availableLocales.sort()).toEqual(['en-US', 'zh-CN'])
  })

  it('keeps the active locale and persistence unchanged when loading fails', async () => {
    vi.doMock('@/locales/en-US', () => {
      throw new Error('locale chunk unavailable')
    })
    vi.resetModules()
    const { i18n, initializeLocale, locale, setLocale } = await import('@/i18n')

    await initializeLocale()
    await expect(setLocale('en-US')).rejects.toThrow()

    expect(locale.value).toBe('zh-CN')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(localStorage.getItem('preferredLocale')).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
  })
})
