import api from './client'
import type { UserEntity } from '@/types/user'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'
import type { TeamMember, TeamOperationLog } from '@/types/team'

export interface AdminTeam {
  id: number
  name: string
  ownerId: number
  storageQuota: number
  storageUsed: number
  createdAt: string
  updatedAt: string
  archivedAt?: string | null
  archivedBy?: number | null
  deletedAt?: string | null
  deletedBy?: number | null
  purgeAfter?: string | null
}

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

export type AdminTeamStatus = 'active' | 'archived' | 'deleted' | 'all'

export const listTeams = (page = 1, size = 20, keyword?: string, status: AdminTeamStatus = 'active') =>
  api.get<ApiResponse<PageResult<AdminTeam>>>('/admin/teams', { params: { page, size, keyword, status } })

export const getTeam = (teamId: number) =>
  api.get<ApiResponse<AdminTeam>>(`/admin/teams/${teamId}`)

export const updateTeamQuota = (teamId: number, storageQuota: number) =>
  api.patch<ApiResponse<AdminTeam>>(`/admin/teams/${teamId}/quota`, { storageQuota })

export const archiveTeam = (teamId: number) =>
  api.post<ApiResponse<AdminTeam>>(`/admin/teams/${teamId}/archive`)

export const unarchiveTeam = (teamId: number) =>
  api.post<ApiResponse<AdminTeam>>(`/admin/teams/${teamId}/unarchive`)

export const restoreTeam = (teamId: number) =>
  api.post<ApiResponse<AdminTeam>>(`/admin/teams/${teamId}/restore`)

export const purgeTeam = (teamId: number) =>
  api.delete<ApiResponse<void>>(`/admin/teams/${teamId}/purge`)

export const listTeamMembers = (teamId: number) =>
  api.get<ApiResponse<TeamMember[]>>(`/admin/teams/${teamId}/members`)

export const listTeamFiles = (
  teamId: number,
  params: {
    parentId?: number | null
    deleted?: boolean
    keyword?: string
    page?: number
    size?: number
    sortBy?: string
    order?: string
  } = {},
) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/admin/teams/${teamId}/files`, {
    params: { ...params, parentId: params.parentId ?? undefined },
  })

export const listTeamAuditLogs = (teamId: number, params: {
  actorUserId?: number
  action?: string
  targetType?: string
  result?: number
  startAt?: string
  endAt?: string
  page?: number
  size?: number
} = {}) =>
  api.get<ApiResponse<PageResult<TeamOperationLog>>>(`/admin/teams/${teamId}/audit-logs`, { params })

export const exportTeamAuditLogs = (teamId: number, params: {
  actorUserId?: number
  action?: string
  targetType?: string
  result?: number
  startAt?: string
  endAt?: string
} = {}) =>
  api.get<Blob>(`/admin/teams/${teamId}/audit-logs/export`, { params, responseType: 'blob' })

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
