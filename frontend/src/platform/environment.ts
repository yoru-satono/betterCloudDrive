export interface QtRuntimeConfig {
  kind: 'qt'
  apiBaseUrl: string
  webBaseUrl: string
}
declare global { interface Window { __BCD_RUNTIME__?: QtRuntimeConfig } }
// A selector, never an authorization grant. Native code still checks every call.
export const isQtRuntime = () => window.__BCD_RUNTIME__?.kind === 'qt'
export const runtimeDefaults = () => window.__BCD_RUNTIME__
export function redirectToLogin() {
  if (isQtRuntime()) window.location.hash = '#/login'
  else if (window.location.pathname !== '/login') window.location.assign('/login')
}
