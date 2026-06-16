import api from './client'
import { toast } from 'vue-sonner'
import { getApiBaseUrl } from '@/config/runtime'
import {
  downloadDesktopFile,
  downloadDesktopFolder,
  isDesktopDownloadRuntime,
} from './desktopDownload'

const getFileNameFromDisposition = (disposition: string | undefined, fallback: string) => {
  if (!disposition) return fallback
  const encoded = disposition.match(/filename\*=[^']*''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return plain ? decodeURIComponent(plain) : fallback
}

const saveBlobResponse = async (response: { data: Blob; headers: Record<string, unknown> }, fallbackFileName: string) => {
  const contentType = String(response.headers['content-type'] || '')
  if (contentType.includes('application/json')) {
    const payload = JSON.parse(await response.data.text()) as { message?: string }
    throw new Error(payload.message || '下载失败')
  }

  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = getFileNameFromDisposition(String(response.headers['content-disposition'] || ''), fallbackFileName)
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const triggerBrowserDownload = (url: string, fileName: string) => {
  const link = document.createElement('a')
  link.href = url.startsWith('http') ? url : `${getApiBaseUrl().replace(/\/api\/v1$/, '')}${url}`
  link.download = fileName
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

export const downloadFile = async (fileId: number, fileName: string) => {
  try {
    if (isDesktopDownloadRuntime()) {
      const saved = await downloadDesktopFile(fileId, fileName)
      if (saved) toast.success('下载完成')
      return
    }

    const { data } = await api.post<{ code: number; data: { url: string } }>(`/download/${fileId}/ticket`, undefined, { suppressToast: true })
    triggerBrowserDownload(data.data.url, fileName)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '下载失败'
    toast.error(msg)
  }
}

export const downloadFolderZip = async (fileId: number, folderName: string) => {
  try {
    if (isDesktopDownloadRuntime()) {
      const saved = await downloadDesktopFolder({ id: fileId, fileName: folderName })
      if (saved) toast.success('文件夹下载完成')
      return
    }

    const { data } = await api.post<{ code: number; data: { url: string } }>(`/download/folders/${fileId}/zip/ticket`, undefined, { suppressToast: true })
    triggerBrowserDownload(data.data.url, `${folderName}.zip`)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '下载失败'
    toast.error(msg)
  }
}

export const previewFileUrl = (fileId: number) => `${getApiBaseUrl()}/preview/${fileId}`

export const previewFile = (fileId: number) =>
  api.get<Blob>(`/preview/${fileId}`, { responseType: 'blob', suppressToast: true })

export const downloadSharedFile = async (shareCode: string, fileId: number, fileName: string, password?: string) => {
  try {
    const res = await api.post<Blob>(
      `/shares/access/${shareCode}/download/${fileId}`,
      password ? { password } : undefined,
      { responseType: 'blob', suppressToast: true }
    )
    await saveBlobResponse(res, fileName)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '下载失败'
    toast.error(msg)
  }
}

export const downloadSharedFolderZip = async (shareCode: string, fileId: number, folderName: string, password?: string) => {
  try {
    const res = await api.post<Blob>(
      `/shares/access/${shareCode}/download/${fileId}/zip`,
      password ? { password } : undefined,
      { responseType: 'blob', suppressToast: true }
    )
    await saveBlobResponse(res, `${folderName}.zip`)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '下载失败'
    toast.error(msg)
  }
}
