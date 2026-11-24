import { mount, flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { toast } from 'vue-sonner'
const settings = vi.hoisted(() => ({
  getApiBaseUrl: () => 'https://old.example/api/v1', getWebBaseUrl: () => 'https://web.example',
  setStoredApiBaseUrl: vi.fn(), setStoredWebBaseUrl: vi.fn(),
}))
vi.mock('../../web/runtime', () => settings)
vi.mock('../../web/auth', () => ({ useAuthStore: () => ({ user: null }) }))
vi.mock('../../web/bridge', () => ({ getCurrentWindow: () => ({}) }))
import DesktopTitleBar from '../../../frontend/src/components/layout/DesktopTitleBar.vue'
it.each(['success', 'failure'])('keeps settings open until native save %s and preserves failed input', async outcome => {
  let resolve!: () => void, reject!: (error: Error) => void
  settings.setStoredApiBaseUrl.mockReturnValueOnce(new Promise<void>((a, b) => { resolve = a; reject = b }))
  const wrapper = mount(DesktopTitleBar, { global: { stubs: { DesktopSettingsPanel: true } } })
  try {
    await wrapper.get('button[aria-controls="desktop-connection-settings"]').trigger('click')
    await wrapper.findAll('input')[0]!.setValue('https://typed.example/api/v1')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(toast.success).not.toHaveBeenCalled()
    expect(settings.setStoredWebBaseUrl).not.toHaveBeenCalled()
    if (outcome === 'success') resolve()
    else reject(new Error('NATIVE_FAILED'))
    await flushPromises()
    if (outcome === 'success') {
      expect(wrapper.find('form').exists()).toBe(false)
      expect(toast.success).toHaveBeenCalled()
    } else {
      expect(wrapper.find('form').exists()).toBe(true)
      expect((wrapper.findAll('input')[0]!.element as HTMLInputElement).value).toBe('https://typed.example/api/v1')
      expect(toast.error).toHaveBeenCalled()
    }
  } finally { wrapper.unmount() }
})
