import api from './client'
import { getApiBaseUrl } from '@/config/runtime'
import type { ApiResponse, PageResult } from '@/types/api'

export interface NotificationEntity {
  id: number
  userId: number
  teamId: number | null
  type: string
  title: string
  content: string
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

export const listNotifications = (page = 1, size = 20, unreadOnly = false, type?: NotificationTypeFilter) => {
  const params: { page: number; size: number; unreadOnly: boolean; type?: NotificationTypeFilter } = { page, size, unreadOnly }
  if (type) params.type = type
  return api.get<ApiResponse<PageResult<NotificationEntity>>>('/notifications', { params })
}

export const getUnreadCount = () =>
  api.get<ApiResponse<number>>('/notifications/unread-count')

export const markNotificationRead = (notificationId: number) =>
  api.post<ApiResponse<void>>(`/notifications/${notificationId}/read`)

export const markAllNotificationsRead = () =>
  api.post<ApiResponse<void>>('/notifications/read-all')

export function createNotificationEventSource() {
  const token = localStorage.getItem('accessToken')
  if (!token || typeof EventSource === 'undefined') return null

  const url = new URL(`${getApiBaseUrl()}/notifications/stream`, window.location.origin)
  url.searchParams.set('accessToken', token)
  return new EventSource(url.toString())
}
