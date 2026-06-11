import api from './client'
import type { FileVersionEntity } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export const listVersions = (fileId: number) =>
  api.get<ApiResponse<FileVersionEntity[]>>(`/files/${fileId}/versions`)

export const getVersionDownloadUrl = (fileId: number, versionId: number) =>
  api.get<ApiResponse<{ url: string }>>(`/files/${fileId}/versions/${versionId}/download`)

export const deleteVersion = (fileId: number, versionId: number) =>
  api.delete<ApiResponse<void>>(`/files/${fileId}/versions/${versionId}`)
