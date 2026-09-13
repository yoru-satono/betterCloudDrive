import { describe, expect, it, vi } from 'vitest'
import { resolveAuthRedirect, type AuthNavigationSession } from '@/router/authGuard'

function session(isLoggedIn: boolean, isAdmin = false): AuthNavigationSession {
  return {
    initializeSession: vi.fn().mockResolvedValue(isLoggedIn),
    isAdmin,
  }
}

describe('auth navigation guard', () => {
  it('redirects an invalid protected session to login', async () => {
    expect(await resolveAuthRedirect({ requiresAuth: true }, session(false))).toBe('/login')
  })

  it('keeps a valid protected session on its destination', async () => {
    expect(await resolveAuthRedirect({ requiresAuth: true }, session(true))).toBeNull()
  })

  it('redirects a restored session away from guest pages', async () => {
    expect(await resolveAuthRedirect({ guest: true }, session(true))).toBe('/files')
  })

  it('rejects authenticated non-admin users from admin routes', async () => {
    expect(await resolveAuthRedirect({ requiresAuth: true, requiresAdmin: true }, session(true))).toBe('/files')
  })
})
