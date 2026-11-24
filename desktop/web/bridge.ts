import { assertIdentity, identityRevision } from './identity'
import { queueMessages, transferMessage } from './errors'
type Payload = Record<string, unknown>
type Listener = (event: { payload: any }) => void
const pending = new Map<string, { resolve: (value: any) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>()
const listeners = new Map<string, Set<Listener>>()
let ready = false
let initialized!: () => void
const initialization = new Promise<void>(resolve => { initialized = resolve })
window.addEventListener('bcd:ready', () => { ready = true; initialized() })
window.addEventListener('bcd:response', ((event: CustomEvent<string>) => {
  const response = JSON.parse(event.detail)
  const item = pending.get(response.id)
  if (!item) return
  clearTimeout(item.timer)
  pending.delete(response.id)
  if (response.error) item.reject(new Error(transferMessage(response.error)))
  else item.resolve(response.result)
}) as EventListener)
window.addEventListener('bcd:event', ((event: CustomEvent<string>) => {
  const { name, payload } = JSON.parse(event.detail)
  if (name === 'transfer:queue-updated') queueMessages(payload)
  if (name === 'transfer:error' && payload?.message) payload.message = transferMessage(payload.message)
  listeners.get(name)?.forEach(listener => listener({ payload }))
}) as EventListener)
window.dispatchEvent(new Event('bcd:hello'))

export async function rawInvoke<T>(command: string, args: Payload = {}, guard?: () => void): Promise<T> {
  if (!ready) await Promise.race([
    initialization,
    new Promise((_, reject) => setTimeout(() => reject(new Error('BRIDGE_NOT_READY')), 10000)),
  ])
  guard?.()
  const id = crypto.randomUUID()
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('COMMAND_TIMEOUT')) }, 45000)
    pending.set(id, { resolve, reject, timer })
    window.dispatchEvent(new CustomEvent('bcd:request', { detail: JSON.stringify({ id, command, args }) }))
  })
}

let connection: Promise<unknown> = Promise.resolve()
let connectionConfirmed = false
let connectionRevision = 0
export const isConnectionConfirmed = () => connectionConfirmed
export const waitForConnection = () => connection
export function configureConnection(apiBaseUrl: string) {
  const revision = ++connectionRevision
  connectionConfirmed = false
  connection = connection.catch(() => undefined).then(async () => {
    const response = await rawInvoke<{ apiBaseUrl: string }>('connection:set', { apiBaseUrl })
    if (response.apiBaseUrl !== apiBaseUrl) throw new Error('CONNECTION_NOT_CONFIRMED')
    if (revision === connectionRevision) connectionConfirmed = true
    return response
  })
  return connection
}
export async function invoke<T>(command: string, args: Payload = {}, guard?: () => void): Promise<T> {
  const scoped = !command.startsWith('window:') && !['app:theme', 'get_desktop_settings', 'save_desktop_settings', 'choose_default_download_directory', 'session:clear'].includes(command)
  const revision = identityRevision()
  const check = () => { if (scoped) assertIdentity(revision); guard?.() }
  // Window controls and revocation must work even after connection failure.
  if (!command.startsWith('window:') && command !== 'app:theme' && command !== 'session:clear') await connection
  const result = await rawInvoke<T>(command, args, check)
  check()
  return queueMessages(result)
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
export type Window = ReturnType<typeof getCurrentWindow>
