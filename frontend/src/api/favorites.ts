import api from './client'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'

export const listFavorites = (page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>('/favorites', { params: { page, size, q } })

export const addFavorite = (fileId: number) =>
  api.post<ApiResponse<void>>(`/favorites/${fileId}`)

export const removeFavorite = (fileId: number) =>
  api.delete<ApiResponse<void>>(`/favorites/${fileId}`)
