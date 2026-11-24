import { expect, it, vi } from 'vitest'
import { initializeDesktop } from '@/platform/desktop'
import { isQtRuntime } from '@/platform/environment'
it('fails a missing Qt handshake explicitly without falling back to Web', async () => {
  vi.useFakeTimers()
  const requests = vi.fn(); window.addEventListener('bcd:request', requests)
  try {
    const boot = initializeDesktop('https://qt.example/api/v1')
    const rejected = expect(boot).rejects.toThrow('BRIDGE_NOT_READY')
    await vi.dynamicImportSettled()
    await vi.advanceTimersByTimeAsync(10_000)
    await rejected
    expect(isQtRuntime()).toBe(true)
    expect(requests).not.toHaveBeenCalled()
  } finally { window.removeEventListener('bcd:request', requests); vi.useRealTimers() }
})
