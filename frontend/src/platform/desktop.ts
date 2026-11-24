/** Browser implementation. The Qt build maps this module to its private bridge. */
export type UnlistenFn = () => void
export function invoke<T>(_command: string, _args?: Record<string, unknown>): Promise<T> {
  return Promise.reject(new Error('DESKTOP_UNAVAILABLE'))
}
export function listen<T>(_name: string, _listener: (event: { payload: T }) => void): Promise<UnlistenFn> {
  return Promise.reject(new Error('DESKTOP_UNAVAILABLE'))
}
export function getCurrentWindow() {
  return {
    minimize: () => invoke<void>('window:minimize'),
    toggleMaximize: () => invoke<void>('window:maximize'),
    close: () => invoke<void>('window:close'),
    startDragging: () => invoke<void>('window:drag'),
  }
}
export type DesktopWindow = ReturnType<typeof getCurrentWindow>
export const setTheme = (theme: string | null) => invoke<void>('app:theme', { theme })
