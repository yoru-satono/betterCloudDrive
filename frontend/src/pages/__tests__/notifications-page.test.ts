import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NotificationsPage from '@/pages/NotificationsPage.vue'
import * as notificationsApi from '@/api/notifications'
import { toast } from 'vue-sonner'
import type { NotificationEntity, NotificationTypeFilter } from '@/api/notifications'

const routerPush = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/api/notifications', () => ({
  listNotifications: vi.fn(),
  getUnreadCount: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}))

const listNotifications = notificationsApi.listNotifications as Mock
const markNotificationRead = notificationsApi.markNotificationRead as Mock
const markAllNotificationsRead = notificationsApi.markAllNotificationsRead as Mock

function page(records: NotificationEntity[], total = records.length) {
  return {
    data: {
      data: {
        records,
        total,
        page: 1,
        size: 20,
        pages: Math.max(1, Math.ceil(total / 20)),
      },
    },
  }
}

function notification(patch: Partial<NotificationEntity> = {}): NotificationEntity {
  return {
    id: 1,
    userId: 2,
    teamId: 10,
    type: 'TEAM_INVITE',
    title: '团队邀请',
    content: '邀请你加入团队',
    link: '/teams/10/settings',
    isRead: false,
    readAt: null,
    createdAt: '2026-01-01T00:00:00',
    ...patch,
  }
}

function mountPage() {
  return mount(NotificationsPage, {
    global: {
      stubs: {
        OSpinner: { template: '<span>loading</span>' },
      },
    },
  })
}

function button(wrapper: ReturnType<typeof mountPage>, label: string) {
  const found = wrapper.findAll('button').find(item => item.text().trim() === label)
  expect(found, `button ${label}`).toBeTruthy()
  return found!
}

beforeEach(() => {
  routerPush.mockReset()
  listNotifications.mockReset().mockResolvedValue(page([notification()]))
  markNotificationRead.mockReset().mockResolvedValue({ data: { data: null } })
  markAllNotificationsRead.mockReset().mockResolvedValue({ data: { data: null } })
})

describe('NotificationsPage', () => {
  it('filters by unread state and notification type', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(listNotifications).toHaveBeenCalledWith(1, 20, false, undefined)
    expect(wrapper.text()).toContain('团队邀请')

    await button(wrapper, '仅未读').trigger('click')
    await flushPromises()
    expect(listNotifications).toHaveBeenLastCalledWith(1, 20, true, undefined)
    expect(wrapper.text()).toContain('全部通知')

    await wrapper.find('select.notifications-page__type').setValue('TEAM_QUOTA_ALERT' satisfies NotificationTypeFilter)
    await flushPromises()
    expect(listNotifications).toHaveBeenLastCalledWith(1, 20, true, 'TEAM_QUOTA_ALERT')
  })

  it('marks a notification read and navigates to its link', async () => {
    const dispatchEvent = vi.spyOn(window, 'dispatchEvent')
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('.notification-row').trigger('click')
    await flushPromises()

    expect(markNotificationRead).toHaveBeenCalledWith(1)
    expect(routerPush).toHaveBeenCalledWith('/teams/10/settings')
    expect(dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'bcd:notifications-changed' }))
  })

  it('marks all notifications read and shows contextual empty states', async () => {
    listNotifications.mockResolvedValue(page([]))
    const dispatchEvent = vi.spyOn(window, 'dispatchEvent')
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无通知')

    await button(wrapper, '仅未读').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无未读通知')

    await wrapper.find('select.notifications-page__type').setValue('TEAM_DELETE' satisfies NotificationTypeFilter)
    await flushPromises()
    expect(wrapper.text()).toContain('暂无匹配的未读通知')

    await button(wrapper, '全部已读').trigger('click')
    await flushPromises()

    expect(markAllNotificationsRead).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('通知已全部标记为已读')
    expect(dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'bcd:notifications-changed' }))
  })

  it('reloads current page when realtime notification event arrives', async () => {
    const wrapper = mountPage()
    await flushPromises()
    listNotifications.mockClear()
    listNotifications.mockResolvedValue(page([notification({ id: 2, title: '新通知' })]))

    window.dispatchEvent(new CustomEvent('bcd:notifications-changed', { detail: { unreadCount: 1 } }))
    await flushPromises()

    expect(listNotifications).toHaveBeenCalledWith(1, 20, false, undefined)
    expect(wrapper.text()).toContain('新通知')
  })
})
