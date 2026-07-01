import api from './client'
import type { FileVersionEntity } from '@/types/file'
import type { ApiResponse } from '@/types/api'

const saveBlobResponse = async (response: { data: Blob; headers: Record<string, unknown> }, fallbackFileName: string) => {
  const contentType = String(response.headers['content-type'] || '')
  if (contentType.includes('application/json')) {
    const payload = JSON.parse(await response.data.text()) as { message?: string }
    throw new Error(payload.message || '下载失败')
  }

  const disposition = String(response.headers['content-disposition'] || '')
  const encoded = disposition.match(/filename\*=[^']*''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  const fileName = encoded ? decodeURIComponent(encoded) : plain ? decodeURIComponent(plain) : fallbackFileName

  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export const listVersions = (fileId: number) =>
  api.get<ApiResponse<FileVersionEntity[]>>(`/files/${fileId}/versions`)

export const deleteVersion = (fileId: number, versionNumber: number) =>
  api.delete<ApiResponse<void>>(`/files/${fileId}/versions/${versionNumber}`)

export const restoreVersion = (fileId: number, versionNumber: number) =>
  api.post<ApiResponse<void>>(`/files/${fileId}/versions/${versionNumber}/restore`)

export const downloadVersion = async (fileId: number, versionNumber: number, fileName: string) => {
  const res = await api.get<Blob>(`/files/${fileId}/versions/${versionNumber}/download`, { responseType: 'blob', suppressToast: true })
  await saveBlobResponse(res, `v${versionNumber}-${fileName}`)
}

export const listTeamVersions = (teamId: number, fileId: number) =>
  api.get<ApiResponse<FileVersionEntity[]>>(`/teams/${teamId}/files/${fileId}/versions`)

export const deleteTeamVersion = (teamId: number, fileId: number, versionNumber: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/files/${fileId}/versions/${versionNumber}`)

export const restoreTeamVersion = (teamId: number, fileId: number, versionNumber: number) =>
  api.post<ApiResponse<void>>(`/teams/${teamId}/files/${fileId}/versions/${versionNumber}/restore`)

export const downloadTeamVersion = async (teamId: number, fileId: number, versionNumber: number, fileName: string) => {
  const res = await api.get<Blob>(`/teams/${teamId}/files/${fileId}/versions/${versionNumber}/download`, { responseType: 'blob', suppressToast: true })
  await saveBlobResponse(res, `v${versionNumber}-${fileName}`)
}
