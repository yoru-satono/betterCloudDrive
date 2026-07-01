import api from './client'
import type { UploadSession } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export interface UploadContext {
  teamId?: number
}

const uploadBase = (context?: UploadContext) =>
  context?.teamId ? `/teams/${context.teamId}/upload` : '/upload'

export const instantUpload = (parentId: number | null, fileName: string, fileSize: number, md5: string, mimeType?: string, context?: UploadContext) =>
  api.post<ApiResponse<{ fileId: number; instant: true }>>(`${uploadBase(context)}/instant`, { parentId, fileName, fileSize, md5Hash: md5 }, { suppressToast: true })

export const initMultipart = (parentId: number | null, fileName: string, fileSize: number, md5Hash: string, totalChunks: number, mimeType?: string, context?: UploadContext) =>
  api.post<ApiResponse<UploadSession>>(`${uploadBase(context)}/init`, { parentId, fileName, fileSize, md5Hash, totalChunks }, { suppressToast: true })

export const uploadChunk = (uploadId: string, chunkIndex: number, data: Blob, onProgress?: (pct: number) => void, context?: UploadContext) => {
  const form = new FormData()
  form.append('file', data)
  return api.post<ApiResponse<{ chunkNumber: number }>>(`${uploadBase(context)}/${uploadId}/chunk`, form, {
    params: { chunkNumber: chunkIndex },
    headers: { 'Content-Type': 'multipart/form-data' },
    suppressToast: true,
    onUploadProgress: onProgress ? (e) => { if (e.total) onProgress(Math.round((e.loaded / e.total) * 100)) } : undefined
  })
}

export const completeMultipart = (uploadId: string, context?: UploadContext) =>
  api.post<ApiResponse<{ fileId: number }>>(`${uploadBase(context)}/${uploadId}/complete`, undefined, { suppressToast: true })

export const abortMultipart = (uploadId: string, context?: UploadContext) =>
  api.post<ApiResponse<void>>(`${uploadBase(context)}/${uploadId}/cancel`, undefined, { suppressToast: true })

export const getUploadStatus = (uploadId: string, context?: UploadContext) =>
  api.get<ApiResponse<UploadSession>>(`${uploadBase(context)}/${uploadId}/status`, { suppressToast: true })
