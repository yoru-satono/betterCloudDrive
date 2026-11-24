import type { OnlyOfficeWindow } from '@/types/office'
type Factory = NonNullable<OnlyOfficeWindow['DocsAPI']>['DocEditor']
const scripts = new Map<string, Promise<Factory>>()
export function loadOfficeScript(base: string): Promise<Factory> {
  const parsed = new URL(base)
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) return Promise.reject(new Error('OFFICE_SCRIPT_FAILED'))
  const src = base.replace(/\/+$/, '') + '/web-apps/apps/api/documents/api.js'
  if (scripts.has(src)) return scripts.get(src)!
  const promise = new Promise<Factory>((resolve, reject) => {
    let script = Array.from(document.querySelectorAll<HTMLScriptElement>('script[data-onlyoffice-api]')).find(item => item.src === src)
    const factory = () => (window as OnlyOfficeWindow).DocsAPI?.DocEditor
    if (script?.dataset.loaded === 'true' && factory()) { resolve(factory()!); return }
    if (!script) {
      script = document.createElement('script')
      script.src = src
      script.async = true
      script.dataset.onlyofficeApi = src
    }
    const selected = script
    const timer = setTimeout(fail, 30000)
    function cleanup() { clearTimeout(timer); selected.removeEventListener('load', loaded); selected.removeEventListener('error', fail) }
    function fail() { cleanup(); selected.remove(); reject(new Error('OFFICE_SCRIPT_FAILED')) }
    function loaded() {
      const constructor = factory()
      if (!constructor) { fail(); return }
      cleanup()
      selected.dataset.loaded = 'true'
      resolve(constructor)
    }
    selected.addEventListener('load', loaded)
    selected.addEventListener('error', fail)
    if (!selected.isConnected) document.head.appendChild(selected)
  })
  scripts.set(src, promise)
  void promise.catch(() => { if (scripts.get(src) === promise) scripts.delete(src) })
  return promise
}
