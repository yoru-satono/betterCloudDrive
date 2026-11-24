import { expect, it, vi } from 'vitest'
import { invoke } from '@/platform/desktop'
import { invalidateIdentity } from '@/platform/identity'
it('captures identity before lazy transport loading and drops a command after logout', async () => {
  const requests = vi.fn(); window.addEventListener('bcd:request', requests)
  try {
    const response = invoke('get_transfer_queue')
    const rejected = expect(response).rejects.toThrow('SESSION_CHANGED')
    invalidateIdentity('logout')
    await rejected
    expect(requests).not.toHaveBeenCalled()
  } finally { window.removeEventListener('bcd:request', requests) }
})
