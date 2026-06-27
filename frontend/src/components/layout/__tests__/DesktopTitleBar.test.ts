import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DesktopTitleBar from '@/components/layout/DesktopTitleBar.vue'
import { useAuthStore } from '@/stores/auth'

const minimize = vi.fn()
const toggleMaximize = vi.fn()
const close = vi.fn()
const startDragging = vi.fn(() => Promise.resolve())
const getApiBaseUrl = vi.hoisted(() => vi.fn(() => 'http://localhost:8080/api/v1'))
const getWebBaseUrl = vi.hoisted(() => vi.fn(() => 'http://localhost:3000'))
const setStoredApiBaseUrl = vi.hoisted(() => vi.fn())
const setStoredWebBaseUrl = vi.hoisted(() => vi.fn())
const toastSuccess = vi.hoisted(() => vi.fn())
const toastError = vi.hoisted(() => vi.fn())
const desktopSettings = vi.hoisted(() => ({
  uploadLimitBytesPerSec: null,
  downloadLimitBytesPerSec: null,
  maxConcurrentUploads: 3,
  maxConcurrentDownloads: 3,
  defaultDownloadDir: null,
  proxyMode: 'system',
  proxyUrl: '',
  proxyUsername: '',
  proxyPassword: '',
}))
const getDesktopSettings = vi.hoisted(() => vi.fn())
const saveDesktopSettings = vi.hoisted(() => vi.fn())
const chooseDefaultDownloadDirectory = vi.hoisted(() => vi.fn())
let pinia: ReturnType<typeof createPinia>

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({
    minimize,
    toggleMaximize,
    close,
    startDragging,
  }),
}))

vi.mock('@/config/runtime', () => ({
  getApiBaseUrl,
  getWebBaseUrl,
  setStoredApiBaseUrl,
  setStoredWebBaseUrl,
}))

vi.mock('@/api/desktopSettings', () => ({
  DEFAULT_DESKTOP_SETTINGS: desktopSettings,
  getDesktopSettings,
  saveDesktopSettings,
  chooseDefaultDownloadDirectory,
  isDesktopSettingsRuntime: () => true,
  bytesToMbps: (value: number | null | undefined) => {
    if (!value) return ''
    const mbps = value / 1024 / 1024
    return Number.isInteger(mbps) ? String(mbps) : String(mbps)
  },
  mbpsToBytes: (value: string) => {
    const parsed = Number(value)
    if (!Number.isFinite(parsed) || parsed <= 0) return null
    return Math.round(parsed * 1024 * 1024)
  },
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: toastSuccess,
    error: toastError,
  },
}))

describe('DesktopTitleBar', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    localStorage.clear()
    minimize.mockClear()
    toggleMaximize.mockClear()
    close.mockClear()
    startDragging.mockClear()
    getApiBaseUrl.mockClear().mockReturnValue('http://localhost:8080/api/v1')
    getWebBaseUrl.mockClear().mockReturnValue('http://localhost:3000')
    setStoredApiBaseUrl.mockClear()
    setStoredWebBaseUrl.mockClear()
    toastSuccess.mockClear()
    toastError.mockClear()
    getDesktopSettings.mockReset().mockResolvedValue({ ...desktopSettings })
    saveDesktopSettings.mockReset().mockImplementation(async settings => settings)
    chooseDefaultDownloadDirectory.mockReset().mockResolvedValue(null)
  })

  function mountTitleBar() {
    return mount(DesktopTitleBar, {
      global: {
        plugins: [pinia],
        stubs: { Teleport: true },
      },
    })
  }

  it('calls Tauri window controls from its buttons', async () => {
    const wrapper = mountTitleBar()

    await wrapper.get('button[aria-label="最小化"]').trigger('click')
    await wrapper.get('button[aria-label="最大化或还原"]').trigger('click')
    await wrapper.get('button[aria-label="关闭"]').trigger('click')

    expect(minimize).toHaveBeenCalledTimes(1)
    expect(toggleMaximize).toHaveBeenCalledTimes(1)
    expect(close).toHaveBeenCalledTimes(1)
  })

  it('marks the titlebar as a Tauri drag region', () => {
    const wrapper = mountTitleBar()

    expect(wrapper.get('[data-testid="desktop-titlebar"]').attributes('data-tauri-drag-region')).toBe('')
  })

  it('starts window dragging from the title area', async () => {
    const wrapper = mountTitleBar()

    const event = typeof PointerEvent === 'function'
      ? new PointerEvent('pointerdown', { button: 0 })
      : new MouseEvent('pointerdown', { button: 0 })
    wrapper.get('.desktop-titlebar__brand').element.dispatchEvent(event)

    expect(startDragging).toHaveBeenCalledTimes(1)
  })

  it('saves API and web share addresses from the titlebar settings panel', async () => {
    const wrapper = mountTitleBar()

    await wrapper.get('button[aria-label="连接设置"]').trigger('click')

    const inputs = wrapper.findAll('input')
    expect(inputs).toHaveLength(2)
    expect(inputs[0].element.value).toBe('http://localhost:8080/api/v1')
    expect(inputs[1].element.value).toBe('http://localhost:3000')

    await inputs[0].setValue('http://nas.local:8080/api/v1/')
    await inputs[1].setValue('http://nas.local:3000/')
    await wrapper.get('form[aria-label="连接设置"]').trigger('submit')

    expect(setStoredApiBaseUrl).toHaveBeenCalledWith('http://nas.local:8080/api/v1/')
    expect(setStoredWebBaseUrl).toHaveBeenCalledWith('http://nas.local:3000/')
    expect(toastSuccess).toHaveBeenCalledWith('连接设置已保存')
    expect(wrapper.find('form[aria-label="连接设置"]').exists()).toBe(false)
  })

  it('does not start window dragging from the titlebar settings button', async () => {
    const wrapper = mountTitleBar()

    await wrapper.get('button[aria-label="连接设置"]').trigger('click')

    expect(startDragging).not.toHaveBeenCalled()
  })

  it('opens desktop client settings from the titlebar after login', async () => {
    localStorage.setItem('accessToken', 'token')
    const wrapper = mountTitleBar()

    await wrapper.get('button[aria-label="客户端设置"]').trigger('click')
    await flushPromises()

    expect(getDesktopSettings).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('客户端设置')
    expect(wrapper.text()).toContain('传输')
    expect(wrapper.text()).toContain('下载位置')
    expect(wrapper.text()).toContain('代理')
  })

  it('saves desktop client settings from the titlebar modal', async () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      email: null,
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 0,
      storageQuota: 1024,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }
    const wrapper = mountTitleBar()

    await wrapper.get('button[aria-label="客户端设置"]').trigger('click')
    await flushPromises()
    const saveButton = wrapper.findAll('button').find(button => button.text() === '保存')
    expect(saveButton).toBeTruthy()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(saveDesktopSettings).toHaveBeenCalledWith({
      ...desktopSettings,
      uploadLimitBytesPerSec: null,
      downloadLimitBytesPerSec: null,
      maxConcurrentUploads: 3,
      maxConcurrentDownloads: 3,
    })
    expect(toastSuccess).toHaveBeenCalledWith('设置已保存')
  })
})
