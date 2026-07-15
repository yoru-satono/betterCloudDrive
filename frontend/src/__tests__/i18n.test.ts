import { describe, expect, it, vi } from 'vitest'
import { resolveMessage, setLocale } from '@/i18n'

describe('semantic message resolver', () => {
  it('renders stable keys with structured interpolation values', () => {
    expect(resolveMessage('notification.team_quota_alert.content', { teamName: '产品组', threshold: 90 }))
      .toBe('团队“产品组”存储用量已达到 90%')
  })

  it('logs unknown keys and uses a local fallback without backend text', () => {
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    expect(resolveMessage('server.secret.diagnostic')).toContain('操作失败')
    expect(warning).toHaveBeenCalledWith('[i18n] Unknown server message key', 'server.secret.diagnostic')
    warning.mockRestore()
  })

  it('switches semantic messages, persistence, and document language to English', () => {
    setLocale('en-US')
    expect(resolveMessage('error.unauthorized')).toBe('Please sign in first')
    expect(localStorage.getItem('preferredLocale')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
    setLocale('zh-CN')
  })
})
