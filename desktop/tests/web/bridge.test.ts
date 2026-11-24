import { expect, it } from 'vitest'
import { configureConnection, invoke } from '../../web/bridge'

it('recovers a failed native connection gate and keeps revocation/window controls available', async () => {
  window.dispatchEvent(new Event('bcd:ready'))
  const requests: any[] = []
  const capture = ((event: CustomEvent<string>) => requests.push(JSON.parse(event.detail))) as EventListener
  window.addEventListener('bcd:request', capture)
  const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
  const respond = (request: any, result: unknown, error?: string) => window.dispatchEvent(new CustomEvent('bcd:response', { detail: JSON.stringify({ id: request.id, result, error }) }))
  try {
    const failed = configureConnection('https://first.example/api/v1')
    const rejected = expect(failed).rejects.toThrow('CONNECTION_NOT_CONFIRMED')
    await flush()
    respond(requests.shift(), { apiBaseUrl: 'https://wrong.example/api/v1' })
    await rejected
    await expect(invoke('get_transfer_queue')).rejects.toThrow('CONNECTION_NOT_CONFIRMED')
    const clear = invoke('session:clear'), minimize = invoke('window:minimize')
    await flush()
    expect(requests.map(r => r.command)).toEqual(['session:clear', 'window:minimize'])
    requests.splice(0).forEach(r => respond(r, null))
    await Promise.all([clear, minimize])
    const retry = configureConnection('https://retry.example/api/v1')
    await flush()
    respond(requests.shift(), { apiBaseUrl: 'https://retry.example/api/v1' })
    await retry
    const queue = invoke('get_transfer_queue')
    await flush()
    respond(requests.shift(), { tasks: [] })
    expect(await queue).toEqual({ tasks: [] })
  } finally { window.removeEventListener('bcd:request', capture) }
})
it('does not dispatch an identity command invalidated while waiting for connection', async () => {
  let active = true
  window.dispatchEvent(new Event('bcd:ready'))
  const requests: any[] = []
  const capture = ((event: CustomEvent<string>) => requests.push(JSON.parse(event.detail))) as EventListener
  window.addEventListener('bcd:request', capture)
  const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
  try {
    const connection = configureConnection('https://next.example/api/v1')
    const syncing = invoke('session:sync', { token: 'synthetic', apiBaseUrl: 'https://next.example/api/v1' }, () => { if (!active) throw new Error('SESSION_CHANGED') })
    const rejected = expect(syncing).rejects.toThrow('SESSION_CHANGED')
    await flush()
    active = false
    const request = requests.shift()
    window.dispatchEvent(new CustomEvent('bcd:response', { detail: JSON.stringify({ id: request.id, result: { apiBaseUrl: 'https://next.example/api/v1' } }) }))
    await connection
    await rejected
    expect(requests).toEqual([])
  } finally { window.removeEventListener('bcd:request', capture) }
})
it('drops a transfer response delivered after logout', async () => {
  const { invalidateIdentity } = await import('../../web/identity')
  const requests: any[] = []
  const capture = ((event: CustomEvent<string>) => requests.push(JSON.parse(event.detail))) as EventListener
  window.addEventListener('bcd:request', capture)
  try {
    const response = invoke('get_transfer_queue')
    const rejected = expect(response).rejects.toThrow('SESSION_CHANGED')
    for (let i = 0; i < 6; i++) await Promise.resolve()
    const request = requests.shift()
    invalidateIdentity('logout')
    window.dispatchEvent(new CustomEvent('bcd:response', { detail: JSON.stringify({ id: request.id, result: { tasks: [{ name: 'old identity' }] } }) }))
    await rejected
  } finally { window.removeEventListener('bcd:request', capture) }
})
