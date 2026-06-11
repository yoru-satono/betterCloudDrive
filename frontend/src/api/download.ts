import api from './client'
import type { ApiResponse } from '@/types/api'

export const getDownloadUrl = (fileId: number) =>
  api.get<ApiResponse<{ url: string; fileName: string }>>(`/download/${fileId}`)

export const downloadFile = async (fileId: number, fileName: string) => {
  const { data } = await getDownloadUrl(fileId)
  const link = document.createElement('a')
  link.href = data.data.url
  link.download = data.data.fileName || fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}
