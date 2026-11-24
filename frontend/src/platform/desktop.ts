import { isQtRuntime } from './environment'
import { identityRevision, assertIdentity } from './identity'
type Payload = Record<string, unknown>
type Guard = () => void
export type UnlistenFn = () => void
let selected: typeof import('./qt') | undefined
let transport: Promise<typeof import('./qt')> | undefined
function native() {
  if (!isQtRuntime()) return Promise.reject(new Error('DESKTOP_UNAVAILABLE'))
  return transport ??= import('./qt').then(module => { selected = module; return module })
}
export async function invoke<T>(command: string, args: Payload = {}, guard?: Guard): Promise<T> {
  const revision = identityRevision()
  const scoped = !command.startsWith('window:') && !['app:theme', 'get_desktop_settings', 'save_desktop_settings', 'choose_default_download_directory', 'session:clear'].includes(command)
  const check = () => { if (scoped) assertIdentity(revision); guard?.() }
  const module = await native()
  check()
  const result = await module.invoke<T>(command, args, check)
  check()
  return result
}
export async function rawInvoke<T>(command: string, args: Payload = {}, guard?: Guard): Promise<T> {
  return (await native()).rawInvoke<T>(command, args, guard)
}
export async function listen<T>(name: string, listener: (event: { payload: T }) => void): Promise<UnlistenFn> {
  return (await native()).listen<T>(name, listener)
}
export async function configureConnection(base: string) { return (await native()).configureConnection(base) }
export async function waitForConnection() {
  if (isQtRuntime()) await (await native()).waitForConnection()
}
export async function initializeDesktop(base: string) { if (isQtRuntime()) await configureConnection(base) }
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

export const isConnectionConfirmed = () => !isQtRuntime() || !!selected?.isConnectionConfirmed()
