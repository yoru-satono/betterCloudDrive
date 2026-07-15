import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppHeader from '@/components/layout/AppHeader.vue'
import { useSearchContextStore } from '@/stores/searchContext'

const route = vi.hoisted(() => ({ name: 'Files' as string }))
const push = vi.hoisted(() => vi.fn())
const notificationApi = vi.hoisted(() => ({
  getUnreadCount: vi.fn(),
  createNotificationEventSource: vi.fn(),
}))

vi.mock('@/api/notifications', () => notificationApi)

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

describe('AppHeader search context', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    route.name = 'Files'
    push.mockReset()
    notificationApi.getUnreadCount.mockReset().mockResolvedValue({ data: { data: 0 } })
    notificationApi.createNotificationEventSource.mockReset().mockResolvedValue(null)
  })

  it.each([
    ['Files', '搜索文件...'],
    ['Favorites', '搜索收藏...'],
    ['Shares', '搜索分享码、文件 ID 或状态...'],
    ['Tags', '搜索标签名称...'],
    ['RecycleBin', '搜索回收站...'],
  ])('shows the %s search placeholder', (routeName, placeholder) => {
    route.name = routeName
    const wrapper = mount(AppHeader, {
      global: {
        stubs: {
          OButton: { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.text()).toContain(placeholder)
  })

  it('shows active tag file scope in tags context', () => {
    route.name = 'Tags'
    useSearchContextStore().setActiveTag({
      id: 7,
      userId: 1,
      tagName: 'work',
      color: '#00d4aa',
      fileCount: 1,
      createdAt: '2026-01-01T00:00:00',
    })

    const wrapper = mount(AppHeader, {
      global: {
        stubs: {
          OButton: { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.text()).toContain('搜索标签或「work」下文件...')
  })

  it('connects the ticket-backed stream, updates unread count, and closes on unmount', async () => {
    const listeners = new Map<string, (event: Event) => void>()
    const source = {
      addEventListener: vi.fn((name: string, listener: (event: Event) => void) => listeners.set(name, listener)),
      removeEventListener: vi.fn(),
      close: vi.fn(),
    }
    notificationApi.getUnreadCount.mockResolvedValue({ data: { data: 2 } })
    notificationApi.createNotificationEventSource.mockResolvedValue(source)
    const wrapper = mount(AppHeader, { global: { stubs: { OButton: { template: '<button><slot /></button>' } } } })
    await flushPromises()

    expect(notificationApi.createNotificationEventSource).toHaveBeenCalledOnce()
    expect(wrapper.find('.app-header__badge').text()).toBe('2')
    listeners.get('notifications-changed')?.(new MessageEvent('notifications-changed', { data: '{"unreadCount":5}' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.app-header__badge').text()).toBe('5')

    wrapper.unmount()
    expect(source.close).toHaveBeenCalledOnce()
    expect(source.removeEventListener).toHaveBeenCalledTimes(2)
  })

  it('keeps the header usable when stream ticket creation fails', async () => {
    notificationApi.createNotificationEventSource.mockRejectedValue(new Error('ticket unavailable'))
    const wrapper = mount(AppHeader, { global: { stubs: { OButton: { template: '<button><slot /></button>' } } } })
    await flushPromises()
    expect(wrapper.find('header').exists()).toBe(true)
    expect(wrapper.find('.app-header__badge').exists()).toBe(false)
    wrapper.unmount()
  })
})
