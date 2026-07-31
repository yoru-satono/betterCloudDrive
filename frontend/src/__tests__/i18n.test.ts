import { describe, expect, it, vi } from 'vitest'
import { resolveMessage, setLocale } from '@/i18n'
import zhHans from '@/locales/zh-Hans'
import enUS from '@/locales/en-US'

function leafKeys(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object') return [prefix]
  return Object.entries(value).flatMap(([key, child]) => leafKeys(child, prefix ? `${prefix}.${key}` : key))
}

function leafMessages(value: unknown, prefix = ''): Array<[string, string]> {
  if (typeof value === 'string') return [[prefix, value]]
  if (!value || typeof value !== 'object') return []
  return Object.entries(value).flatMap(([key, child]) => leafMessages(child, prefix ? `${prefix}.${key}` : key))
}

function placeholders(message: string): string[] {
  return [...message.matchAll(/\{([^}]+)\}/g)].map(match => match[1]).sort()
}

describe('semantic message resolver', () => {
  it('renders stable keys with structured interpolation values', () => {
    expect(resolveMessage('notification.teamQuotaAlert.content', { teamName: '产品组', threshold: 90 }))
      .toBe('团队“产品组”存储用量已达到 90%')
  })

  it('logs unknown keys and uses a local fallback without backend text', () => {
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    expect(resolveMessage('server.secret.diagnostic')).toContain('操作失败')
    expect(warning).toHaveBeenCalledWith('[i18n] Unknown server message key', 'server.secret.diagnostic')
    warning.mockRestore()
  })

  it('switches semantic messages, persistence, and document language to English', async () => {
    await setLocale('en-US')
    expect(resolveMessage('error.unauthorized')).toBe('Please sign in first')
    expect(localStorage.getItem('preferredLocale')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
    await setLocale('zh-Hans')
  })

  it('keeps Chinese and English message packs structurally complete', () => {
    expect(leafKeys(enUS).sort()).toEqual(leafKeys(zhHans).sort())
  })

  it('keeps interpolation contracts aligned and rejects legacy hash keys', () => {
    const chinese = new Map(leafMessages(zhHans))
    const english = new Map(leafMessages(enUS))
    for (const [key, message] of chinese) {
      expect(key).not.toMatch(/(?:^|\.)migrated(?:\.|$)|(?:^|\.)m_[0-9a-f]+(?:\.|$)/)
      expect(key.split('.').length).toBeGreaterThanOrEqual(2)
      expect(placeholders(english.get(key)!)).toEqual(placeholders(message))
    }
  })

  it('uses a semantic login error instead of the authentication-required copy', async () => {
    await setLocale('en-US')
    expect(resolveMessage('error.invalidCredentials')).toBe('Incorrect username or password')
    await setLocale('zh-Hans')
  })
})
