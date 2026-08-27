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
    expect([...SUPPORTED_LOCALES].sort()).toEqual(['en-US', 'zh-Hans'])
    expect(resolveSupportedLocale('en-us')).toBe('en-US')
    expect(resolveSupportedLocale('zh-Hant')).toBe('zh-Hans')
    expect(resolveSupportedLocale('zh-CN')).toBe('zh-Hans')
    expect(resolveSupportedLocale('zh-SG')).toBe('zh-Hans')
    expect(resolveSupportedLocale('fr-FR')).toBeUndefined()
    expect(i18n.global.availableLocales).toEqual(['zh-Hans'])

    await setLocale('en-US')
    expect(i18n.global.availableLocales.sort()).toEqual(['en-US', 'zh-Hans'])
  })

  it('keeps the active locale and persistence unchanged when loading fails', async () => {
    vi.doMock('@/locales/en-US', () => {
      throw new Error('locale chunk unavailable')
    })
    vi.resetModules()
    const { i18n, initializeLocale, locale, setLocale } = await import('@/i18n')

    await initializeLocale()
    await expect(setLocale('en-US')).rejects.toThrow()

    expect(locale.value).toBe('zh-Hans')
    expect(i18n.global.locale.value).toBe('zh-Hans')
    expect(localStorage.getItem('preferredLocale')).toBe('zh-Hans')
    expect(document.documentElement.lang).toBe('zh-Hans')
  })
})
