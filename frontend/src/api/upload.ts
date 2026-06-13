import api from './client'
import type { UploadSession } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export const instantUpload = (parentId: number | null, fileName: string, fileSize: number, md5: string, mimeType?: string) =>
  api.post<ApiResponse<{ fileId: number; instant: true }>>('/upload/instant', { parentId, fileName, fileSize, md5Hash: md5 }, { suppressToast: true })

export const initMultipart = (parentId: number | null, fileName: string, fileSize: number, md5Hash: string, totalChunks: number, mimeType?: string) =>
  api.post<ApiResponse<UploadSession>>('/upload/init', { parentId, fileName, fileSize, md5Hash, totalChunks }, { suppressToast: true })

export const uploadChunk = (uploadId: string, chunkIndex: number, data: Blob, onProgress?: (pct: number) => void) => {
  const form = new FormData()
  form.append('file', data)
  return api.post<ApiResponse<{ chunkNumber: number }>>(`/upload/${uploadId}/chunk`, form, {
    params: { chunkNumber: chunkIndex },
    headers: { 'Content-Type': 'multipart/form-data' },
    suppressToast: true,
    onUploadProgress: onProgress ? (e) => { if (e.total) onProgress(Math.round((e.loaded / e.total) * 100)) } : undefined
  })
}

export const completeMultipart = (uploadId: string) =>
  api.post<ApiResponse<{ fileId: number }>>(`/upload/${uploadId}/complete`, undefined, { suppressToast: true })

export const abortMultipart = (uploadId: string) =>
  api.post<ApiResponse<void>>(`/upload/${uploadId}/cancel`, undefined, { suppressToast: true })

export const getUploadStatus = (uploadId: string) =>
  api.get<ApiResponse<UploadSession>>(`/upload/${uploadId}/status`, { suppressToast: true })
