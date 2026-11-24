import { expect, it, vi } from 'vitest'
const hooks = vi.hoisted(() => ({ initialize: vi.fn(), mount: vi.fn() }))
vi.mock('@/platform/desktop', () => ({ initializeDesktop: hooks.initialize }))
vi.mock('@/bootstrap', () => ({ mountApplication: hooks.mount }))
it('does not create the application until platform initialization has completed', async () => {
  let ready!: () => void
  hooks.initialize.mockReturnValue(new Promise<void>(resolve => { ready = resolve }))
  await import('@/main')
  expect(hooks.mount).not.toHaveBeenCalled()
  ready()
  await vi.waitFor(() => expect(hooks.mount).toHaveBeenCalledTimes(1))
})
