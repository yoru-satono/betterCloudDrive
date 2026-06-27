import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type { Component } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const isDesktopRuntime = vi.hoisted(() => vi.fn())

vi.mock('@/config/runtime', () => ({
  isDesktopRuntime,
  getApiBaseUrl: () => 'http://localhost:8080/api/v1',
  getWebBaseUrl: () => 'http://localhost:3000',
  setStoredApiBaseUrl: vi.fn(),
  setStoredWebBaseUrl: vi.fn(),
}))

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
    close: vi.fn(),
    startDragging: vi.fn(),
  }),
}))

vi.mock('vue-sonner', () => ({
  Toaster: { template: '<div />' },
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

describe('App desktop shell', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  function mountApp(App: Component) {
    return mount(App, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterView: { template: '<main />' },
          UploadQueue: { template: '<div />' },
          OConfirmDialog: { template: '<div />' },
          Teleport: true,
        },
      },
    })
  }

  it('renders a custom titlebar in Tauri runtime', async () => {
    isDesktopRuntime.mockReturnValue(true)
    const App = (await import('@/App.vue')).default

    const wrapper = mountApp(App)

    expect(wrapper.find('[data-testid="desktop-titlebar"]').exists()).toBe(true)
    expect(wrapper.classes()).toContain('app-shell--desktop')
  })

  it('keeps the browser build undecorated by the desktop shell', async () => {
    vi.resetModules()
    isDesktopRuntime.mockReturnValue(false)
    const App = (await import('@/App.vue')).default

    const wrapper = mountApp(App)

    expect(wrapper.find('[data-testid="desktop-titlebar"]').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('app-shell--desktop')
  })
})
