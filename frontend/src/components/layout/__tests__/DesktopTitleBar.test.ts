import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import DesktopTitleBar from '@/components/layout/DesktopTitleBar.vue'

const minimize = vi.fn()
const toggleMaximize = vi.fn()
const close = vi.fn()
const startDragging = vi.fn(() => Promise.resolve())

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({
    minimize,
    toggleMaximize,
    close,
    startDragging,
  }),
}))

describe('DesktopTitleBar', () => {
  it('calls Tauri window controls from its buttons', async () => {
    const wrapper = mount(DesktopTitleBar)

    await wrapper.get('button[aria-label="最小化"]').trigger('click')
    await wrapper.get('button[aria-label="最大化或还原"]').trigger('click')
    await wrapper.get('button[aria-label="关闭"]').trigger('click')

    expect(minimize).toHaveBeenCalledTimes(1)
    expect(toggleMaximize).toHaveBeenCalledTimes(1)
    expect(close).toHaveBeenCalledTimes(1)
  })

  it('marks the titlebar as a Tauri drag region', () => {
    const wrapper = mount(DesktopTitleBar)

    expect(wrapper.get('[data-testid="desktop-titlebar"]').attributes('data-tauri-drag-region')).toBe('')
  })

  it('starts window dragging from the title area', async () => {
    const wrapper = mount(DesktopTitleBar)

    const event = typeof PointerEvent === 'function'
      ? new PointerEvent('pointerdown', { button: 0 })
      : new MouseEvent('pointerdown', { button: 0 })
    wrapper.get('.desktop-titlebar__brand').element.dispatchEvent(event)

    expect(startDragging).toHaveBeenCalledTimes(1)
  })
})
