import api from './client'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'

export interface ListFilesParams {
  parentId?: number | null
  page?: number
  size?: number
  sortBy?: string
  order?: string
}

export const listFiles = (params: ListFilesParams) =>
  api.get<ApiResponse<PageResult<FileEntity>>>('/files', { params })

export const getFile = (fileId: number) =>
  api.get<ApiResponse<FileEntity>>(`/files/${fileId}`)

export const searchFiles = (q: string, page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<FileEntity>>>('/files/search', { params: { q, page, size } })

export const createFolder = (parentId: number | null, folderName: string) =>
  api.post<ApiResponse<FileEntity>>('/files/folder', { parentId, folderName })

export const renameFile = (fileId: number, newName: string) =>
  api.put<ApiResponse<FileEntity>>(`/files/${fileId}`, { newName })

export const deleteFiles = (fileIds: number[]) =>
  api.delete<ApiResponse<void>>('/files', { data: { fileIds } })

export const moveFile = (fileId: number, targetParentId: number | null) =>
  api.post<ApiResponse<void>>(`/files/${fileId}/move`, { targetParentId })

export const copyFile = (fileId: number, targetParentId: number | null) =>
  api.post<ApiResponse<void>>(`/files/${fileId}/copy`, { targetParentId })
