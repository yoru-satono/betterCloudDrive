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
  createdAt: string
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

export const listLogs = (params: { userId?: number; actionType?: string; page?: number; size?: number; startDate?: string; endDate?: string }) =>
  api.get<ApiResponse<PageResult<OperationLog>>>('/admin/logs', { params })
