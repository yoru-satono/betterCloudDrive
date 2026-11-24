import { expect, it, vi } from 'vitest'
import { loadOfficeScript } from '@/platform/officeScript'
it('deduplicates script requests and removes failed tags so retry can succeed', async () => {
  const base = 'https://office.example', first = loadOfficeScript(base), second = loadOfficeScript(base)
  expect(first).toBe(second)
  const failed = expect(first).rejects.toThrow('OFFICE_SCRIPT_FAILED')
  document.querySelector('script[data-onlyoffice-api]')!.dispatchEvent(new Event('error')); await failed
  expect(document.querySelector('script[data-onlyoffice-api]')).toBeNull()
  const retry = loadOfficeScript(base), ctor = vi.fn(function () { return {} })
  ;(window as any).DocsAPI = { DocEditor: ctor }
  document.querySelector('script[data-onlyoffice-api]')!.dispatchEvent(new Event('load'))
  expect(await retry).toBe(ctor)
})
