import api from './client'
import type { TagEntity } from '@/types/tag'
import type { FileEntity } from '@/types/file'
import type { ApiResponse, PageResult } from '@/types/api'

export const listTags = (q?: string) =>
  api.get<ApiResponse<TagEntity[]>>('/tags', { params: { q } })

export const createTag = (tagName: string, color?: string) =>
  api.post<ApiResponse<TagEntity>>('/tags', { tagName, color })

export const updateTag = (tagId: number, tagName: string, color?: string) =>
  api.put<ApiResponse<TagEntity>>(`/tags/${tagId}`, { tagName, color })

export const deleteTag = (tagId: number) =>
  api.delete<ApiResponse<void>>(`/tags/${tagId}`)

export const addFileTag = (fileId: number, tagId: number) =>
  api.post<ApiResponse<void>>(`/tags/${tagId}/files`, { fileIds: [fileId] })

export const removeFileTag = (fileId: number, tagId: number) =>
  api.delete<ApiResponse<void>>(`/tags/${tagId}/files/${fileId}`)

export const listFilesByTag = (tagId: number, page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/tags/${tagId}/files`, { params: { page, size, q } })
