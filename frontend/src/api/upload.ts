import api from './client'
import SparkMD5 from 'spark-md5'
import { resolveMessage } from '@/i18n'
import type { UploadSession, UploadInitResult } from '@/types/file'
export type { UploadInitResult } from '@/types/file'
import type { ApiResponse } from '@/types/api'

export interface UploadContext {
  teamId?: number
}

const uploadBase = (context?: UploadContext) =>
  context?.teamId ? `/teams/${context.teamId}/upload` : '/upload'

export function parseUploadInitResult(value: unknown, fileSize: number): UploadInitResult {
  const data = value as Partial<{ mode: string; fileId: number; sessionId: string; chunkSize: number; totalChunks: number }> | null
  if (data?.mode === 'INSTANT' && Number.isSafeInteger(data.fileId) && data.fileId! > 0
      && data.sessionId === undefined && data.chunkSize === undefined && data.totalChunks === undefined) {
    return { mode: 'INSTANT', fileId: data.fileId! }
  }
  if (data?.mode === 'MULTIPART' && data.fileId === undefined && typeof data.sessionId === 'string' && data.sessionId.trim()
      && Number.isSafeInteger(data.chunkSize) && data.chunkSize! > 0 && Number.isSafeInteger(data.totalChunks)
      && data.totalChunks === Math.ceil(fileSize / data.chunkSize!)) {
    return { mode: 'MULTIPART', sessionId: data.sessionId, chunkSize: data.chunkSize!, totalChunks: data.totalChunks }
  }
  throw new Error(resolveMessage('error.uploadFailed'))
}

export async function initUpload(parentId: number | null, fileName: string, fileSize: number, md5Hash: string, context?: UploadContext) {
  const response = await api.post<ApiResponse<UploadInitResult>>(`${uploadBase(context)}/init`,
    { parentId, fileName, fileSize, md5Hash }, { suppressToast: true })
  response.data.data = parseUploadInitResult(response.data.data, fileSize)
  return response
}

export const uploadChunk = async (uploadId: string, chunkIndex: number, data: Blob, onProgress?: (pct: number) => void, context?: UploadContext) => {
  const bytes = await new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(data)
  })
  const chunkMd5 = SparkMD5.ArrayBuffer.hash(bytes)
  const form = new FormData()
  form.append('file', data)
  return api.post<ApiResponse<{ chunkNumber: number }>>(`${uploadBase(context)}/${uploadId}/chunk`, form, {
    params: { chunkNumber: chunkIndex, chunkMd5 },
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
