import api from './client'
import type { UploadSession } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export const instantUpload = (parentId: number | null, fileName: string, fileSize: number, md5: string, mimeType?: string) =>
  api.post<ApiResponse<{ fileId: number; existed: boolean }>>('/upload/instant', { parentId, fileName, fileSize, md5, mimeType })

export const initMultipart = (parentId: number | null, fileName: string, totalSize: number, mimeType?: string) =>
  api.post<ApiResponse<UploadSession>>('/upload/multipart/init', { parentId, fileName, totalSize, mimeType })

export const uploadChunk = (uploadId: string, chunkIndex: number, data: Blob, onProgress?: (pct: number) => void) => {
  const form = new FormData()
  form.append('file', data)
  return api.post<ApiResponse<void>>(`/upload/multipart/${uploadId}/chunk/${chunkIndex}`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: onProgress ? (e) => { if (e.total) onProgress(Math.round((e.loaded / e.total) * 100)) } : undefined
  })
}

export const completeMultipart = (uploadId: string) =>
  api.post<ApiResponse<{ fileId: number }>>(`/upload/multipart/${uploadId}/complete`)

export const abortMultipart = (uploadId: string) =>
  api.delete<ApiResponse<void>>(`/upload/multipart/${uploadId}`)

export const getUploadStatus = (uploadId: string) =>
  api.get<ApiResponse<UploadSession>>(`/upload/multipart/${uploadId}/status`)
