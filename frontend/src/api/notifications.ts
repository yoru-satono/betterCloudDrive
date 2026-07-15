import api from './client'
import { getApiBaseUrl } from '@/config/runtime'
import type { ApiResponse, PageResult } from '@/types/api'

export interface NotificationEntity {
  id: number
  userId: number
  teamId: number | null
  type: string
  titleKey: string
  contentKey: string
  messageParams: Record<string, string | number | boolean | null>
  link: string | null
  isRead: boolean
  readAt: string | null
  createdAt: string
}

export type NotificationTypeFilter =
  | 'TEAM_INVITE'
  | 'TEAM_INVITE_ACCEPT'
  | 'TEAM_INVITE_REJECT'
  | 'TEAM_QUOTA_ALERT'
  | 'TEAM_ARCHIVE'
  | 'TEAM_UNARCHIVE'
  | 'TEAM_RESTORE'
  | 'TEAM_PERMISSION_UPDATE'
  | 'TEAM_DELETE'
  | 'TEAM_MEMBER_ADD'
  | 'TEAM_ROLE_UPDATE'
  | 'TEAM_MEMBER_REMOVE'
  | 'TEAM_OWNER_TRANSFER'
  | 'TEAM_OFFICE_SAVE_FAILED'

export type NotificationPreferenceMap = Partial<Record<NotificationTypeFilter, boolean>>

export const listNotifications = (page = 1, size = 20, unreadOnly = false, type?: NotificationTypeFilter) => {
  const params: { page: number; size: number; unreadOnly: boolean; type?: NotificationTypeFilter } = { page, size, unreadOnly }
  if (type) params.type = type
  return api.get<ApiResponse<PageResult<NotificationEntity>>>('/notifications', { params })
}

export const getUnreadCount = () =>
  api.get<ApiResponse<number>>('/notifications/unread-count')

export const getNotificationPreferences = () =>
  api.get<ApiResponse<Record<NotificationTypeFilter, boolean>>>('/notifications/preferences')

export const updateNotificationPreferences = (preferences: NotificationPreferenceMap) =>
  api.put<ApiResponse<Record<NotificationTypeFilter, boolean>>>('/notifications/preferences', { preferences })

export const markNotificationRead = (notificationId: number) =>
  api.post<ApiResponse<void>>(`/notifications/${notificationId}/read`)

export const markAllNotificationsRead = () =>
  api.post<ApiResponse<void>>('/notifications/read-all')

export async function createNotificationEventSource() {
  if (typeof EventSource === 'undefined') return null
  const response = await api.post<ApiResponse<{ ticket: string; expiresAt: string }>>('/notifications/stream-ticket')
  const url = new URL(`${getApiBaseUrl()}/notifications/stream`, window.location.origin)
  url.searchParams.set('ticket', response.data.data.ticket)
  return new EventSource(url.toString())
}
