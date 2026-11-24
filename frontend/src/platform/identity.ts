// A page-local epoch invalidates asynchronous identity work, including HTTP.
let revision = 0
export const identityRevision = () => revision
export function invalidateIdentity(reason: string) {
  revision += 1
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
  window.dispatchEvent(new CustomEvent('bcd:identity-invalidated', { detail: reason }))
  return revision
}
export function assertIdentity(expected: number) {
  if (expected !== revision) throw new Error('SESSION_CHANGED')
}
