import { expect, it, vi } from 'vitest'
import { getCurrentWindow, invoke, listen, setTheme } from '../desktop'
import { isDesktopRuntime } from '@/config/runtime'

it('keeps browser code out of the native bridge', async () => {
  const request = vi.fn()
  window.addEventListener('bcd:request', request)
  try {
    expect(isDesktopRuntime()).toBe(false)
    await expect(invoke('get_transfer_queue')).rejects.toThrow('DESKTOP_UNAVAILABLE')
    await expect(listen('transfer:queue-updated', () => {})).rejects.toThrow('DESKTOP_UNAVAILABLE')
    await expect(getCurrentWindow().close()).rejects.toThrow('DESKTOP_UNAVAILABLE')
    await expect(setTheme('dark')).rejects.toThrow('DESKTOP_UNAVAILABLE')
    expect(request).not.toHaveBeenCalled()
  } finally { window.removeEventListener('bcd:request', request) }
})
