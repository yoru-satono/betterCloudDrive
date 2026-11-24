// PoC-only adapter. Vite aliases keep these replacements out of production.
type Listener = (event: { payload: any }) => void
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>()
const listeners = new Map<string, Set<Listener>>()
let ready = false
let markReady!: () => void
const initialized = new Promise<void>(resolve => { markReady = resolve })
window.addEventListener('bcd:ready', () => { ready = true; markReady() })
window.addEventListener('bcd:response', ((event: CustomEvent<string>) => {
  const response = JSON.parse(event.detail)
  const item = pending.get(response.id)
  if (!item) return
  clearTimeout(item.timer)
  pending.delete(response.id)
  if (response.error) item.reject(new Error(response.error))
  else item.resolve(response.result)
}) as EventListener)
window.addEventListener('bcd:event', ((event: CustomEvent<string>) => {
  const { name, payload } = JSON.parse(event.detail)
  listeners.get(name)?.forEach(listener => listener({ payload }))
}) as EventListener)
window.dispatchEvent(new Event('bcd:hello'))

export async function invoke<T>(command: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!ready) await Promise.race([initialized, new Promise((_, reject) => setTimeout(() => reject(new Error('BRIDGE_NOT_READY')), 10000))])
  const id = crypto.randomUUID()
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('COMMAND_TIMEOUT')) }, 45000)
    pending.set(id, { resolve, reject, timer })
    window.dispatchEvent(new CustomEvent('bcd:request', { detail: JSON.stringify({ id, command, args }) }))
  })
}
export type UnlistenFn = () => void
export async function listen<T>(name: string, listener: (event: { payload: T }) => void): Promise<UnlistenFn> {
  const group = listeners.get(name) || new Set()
  group.add(listener)
  listeners.set(name, group)
  return () => { group.delete(listener); if (!group.size) listeners.delete(name) }
}
export function getCurrentWindow() {
  return {
    minimize: () => invoke('window:minimize'),
    toggleMaximize: () => invoke('window:maximize'),
    close: () => invoke('window:close'),
    startDragging: () => invoke('window:drag'),
  }
}
export const setTheme = (theme: string | null) => invoke('app:theme', { theme })
// Available only in the dedicated PoC build; useful for reproducible CDP checks.
Object.assign(window, { __BCD_QT_POC__: { invoke, listen } })
