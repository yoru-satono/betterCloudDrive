import api from './client'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'

export const listRecycleBin = (page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<FileEntity>>>('/recycle-bin', { params: { page, size } })

export const restoreFiles = (fileIds: number[]) =>
  api.post<ApiResponse<void>>('/recycle-bin/restore', { fileIds })

export const permanentDelete = (fileIds: number[]) =>
  api.delete<ApiResponse<void>>('/recycle-bin', { data: { fileIds } })

export const emptyRecycleBin = () =>
  api.delete<ApiResponse<void>>('/recycle-bin/empty')
