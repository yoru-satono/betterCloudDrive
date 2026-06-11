import api from './client'
import type { UserEntity } from '@/types/user'
import type { ApiResponse, PageResult } from '@/types/api'

export interface OperationLog {
  id: number
  userId: number
  username: string
  action: string
  targetId: number | null
  targetName: string | null
  detail: string | null
  createdAt: string
}

export interface AdminStats {
  totalUsers: number
  activeUsers: number
  totalFiles: number
  totalStorageUsed: number
  totalStorageQuota: number
  activeShares: number
  todayOperations: number
}

export const getStats = () =>
  api.get<ApiResponse<AdminStats>>('/admin/stats')

export const listUsers = (page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<UserEntity>>>('/admin/users', { params: { page, size, q } })

export const updateUser = (userId: number, data: { isActive?: boolean; storageQuota?: number; role?: string }) =>
  api.put<ApiResponse<UserEntity>>(`/admin/users/${userId}`, data)

export const deleteUser = (userId: number) =>
  api.delete<ApiResponse<void>>(`/admin/users/${userId}`)

export const listLogs = (params: { userId?: number; page?: number; size?: number; startDate?: string; endDate?: string }) =>
  api.get<ApiResponse<PageResult<OperationLog>>>('/admin/logs', { params })
