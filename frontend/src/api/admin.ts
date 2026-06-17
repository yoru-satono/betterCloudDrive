import api from './client'
import type { UserEntity } from '@/types/user'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'

export interface OperationLog {
  id: number
  userId: number
  actionType: string
  targetType: string | null
  targetId: number | null
  detail: string | null
  ipAddress: string | null
  userAgent: string | null
  result: number | null
  durationMs: number | null
  requestId: string | null
  traceId: string | null
  statusCode: number | null
  errorCode: number | null
  createdAt: string
}

export interface SystemLogEntry {
  id: string
  idType: 'traceId' | 'requestId' | 'timestamp'
  traceId: string | null
  requestId: string | null
  timestamp: string | null
  level: string | null
  logger: string | null
  message: string | null
  path: string | null
  method: string | null
  logType: string | null
  grafanaUrl: string
}

export interface AdminStats {
  totalUsers: number
  activeUsers: number
  totalStorageUsed: number
}

export const getStats = () =>
  api.get<ApiResponse<AdminStats>>('/admin/stats')

export const listUsers = (page = 1, size = 20, keyword?: string, status?: number) =>
  api.get<ApiResponse<PageResult<UserEntity>>>('/admin/users', { params: { page, size, keyword, status } })

export const updateUserStatus = (userId: number, status: number) =>
  api.patch<ApiResponse<void>>(`/admin/users/${userId}/status`, { status })

export const updateUserQuota = (userId: number, storageQuota: number) =>
  api.patch<ApiResponse<void>>(`/admin/users/${userId}/quota`, { storageQuota })

export const listUserFiles = (
  userId: number,
  params: { parentId?: number | null; page?: number; size?: number; sortBy?: string; order?: string } = {},
) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/admin/users/${userId}/files`, {
    params: { ...params, parentId: params.parentId ?? undefined },
  })

export const getFile = (fileId: number) =>
  api.get<ApiResponse<FileEntity>>(`/admin/files/${fileId}`)

export const deleteFile = (fileId: number) =>
  api.delete<ApiResponse<void>>(`/admin/files/${fileId}`)

export const listLogs = (params: {
  userId?: number
  actionType?: string
  requestId?: string
  traceId?: string
  statusCode?: number
  result?: number
  page?: number
  size?: number
  startDate?: string
  endDate?: string
}) =>
  api.get<ApiResponse<PageResult<OperationLog>>>('/admin/logs', { params })

export const listSystemLogs = (params: {
  traceId?: string
  requestId?: string
  level?: string
  logType?: string
  keyword?: string
  startTime?: string
  endTime?: string
  limit?: number
}) =>
  api.get<ApiResponse<SystemLogEntry[]>>('/admin/system-logs', { params })

export const createGrafanaSession = () =>
  api.post<ApiResponse<void>>('/admin/grafana/session')
