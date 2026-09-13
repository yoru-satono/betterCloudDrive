import type { RouteMeta } from 'vue-router'

export interface AuthNavigationSession {
  initializeSession: () => Promise<boolean>
  isAdmin: boolean
}

export async function resolveAuthRedirect(meta: RouteMeta, auth: AuthNavigationSession) {
  const isLoggedIn = await auth.initializeSession()

  if (meta.requiresAuth && !isLoggedIn) return '/login'
  if (meta.requiresAdmin && !auth.isAdmin) return '/files'
  if (meta.guest && isLoggedIn) return '/files'
  return null
}
