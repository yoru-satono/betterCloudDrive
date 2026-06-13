import api from './client'
import type { FileVersionEntity } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export const listVersions = (fileId: number) =>
  api.get<ApiResponse<FileVersionEntity[]>>(`/files/${fileId}/versions`)

export const deleteVersion = (fileId: number, versionNumber: number) =>
  api.delete<ApiResponse<void>>(`/files/${fileId}/versions/${versionNumber}`)
