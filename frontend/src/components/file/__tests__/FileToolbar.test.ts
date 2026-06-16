import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const isDesktopRuntime = vi.hoisted(() => vi.fn())

vi.mock('@/config/runtime', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/config/runtime')>()),
  isDesktopRuntime,
}))

describe('FileToolbar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetModules()
  })

  it('shows folder upload only in desktop runtime', async () => {
    isDesktopRuntime.mockReturnValue(true)
    const FileToolbar = (await import('@/components/file/FileToolbar.vue')).default

    const wrapper = mount(FileToolbar)

    expect(wrapper.text()).toContain('上传文件夹')
  })

  it('hides folder upload in browser runtime', async () => {
    isDesktopRuntime.mockReturnValue(false)
    const FileToolbar = (await import('@/components/file/FileToolbar.vue')).default

    const wrapper = mount(FileToolbar)

    expect(wrapper.text()).not.toContain('上传文件夹')
  })
})
