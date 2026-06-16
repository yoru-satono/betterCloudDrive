import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { getApiBaseUrl, isDesktopRuntime } from '@/config/runtime'
import {
  readResumableDirectories,
  readResumableUploads,
  removeResumableUpload,
  saveResumableDirectory,
  saveResumableUpload,
  type ResumableDirectoryRecord,
  type ResumableUploadRecord,
} from './resumableUpload'
import type { UploadItem } from '@/stores/upload'

interface DesktopFolderUploadStart {
  selected: boolean
  batchId?: string
  rootName?: string
}

export interface DesktopUploadItemEvent {
  id: string
  batchId: string
  fileName: string
  displayName: string
  parentId: number | null
  status: UploadItem['status']
  progress: number
  chunkProgress: string
  error?: string | null
  uploadId?: string | null
  fileSize: number
  md5Hash?: string | null
  totalChunks: number
  resumable: boolean
}

export const isDesktopFolderUploadRuntime = () => isDesktopRuntime()

export function getDesktopUploadToken() {
  return localStorage.getItem('accessToken') || ''
}

export async function uploadDesktopFolder(parentId: number | null) {
  return invoke<DesktopFolderUploadStart>('upload_desktop_folder', {
    request: {
      parentId,
      token: getDesktopUploadToken(),
      apiBaseUrl: getApiBaseUrl(),
      resumableUploads: readResumableUploads(),
      resumableDirectories: readResumableDirectories(),
    },
  })
}

export async function cancelDesktopUpload(uploadItemId: string) {
  return invoke<void>('cancel_desktop_upload', { uploadItemId })
}

export async function registerDesktopUploadListeners(handlers: {
  onItemUpdated: (item: DesktopUploadItemEvent) => void
  onBatchCompleted: () => void
  onError: (message: string) => void
}) {
  if (!isDesktopFolderUploadRuntime()) return () => undefined

  const unlisten = await Promise.all([
    listen<DesktopUploadItemEvent>('desktop-upload:item-updated', (event) => {
      handlers.onItemUpdated(event.payload)
      const item = event.payload
      if ((item.status === 'done' || item.status === 'instant') && item.uploadId) {
        removeResumableUpload(item.uploadId)
      }
    }),
    listen<ResumableUploadRecord>('desktop-upload:resumable-record', (event) => {
      saveResumableUpload(event.payload)
    }),
    listen<{ record: ResumableDirectoryRecord }>('desktop-upload:directory-record', (event) => {
      saveResumableDirectory(event.payload.record)
    }),
    listen('desktop-upload:batch-completed', () => {
      handlers.onBatchCompleted()
    }),
    listen<{ message?: string }>('desktop-upload:error', (event) => {
      handlers.onError(event.payload.message || '上传文件夹失败')
    }),
  ])

  return () => {
    unlisten.forEach((dispose: UnlistenFn) => dispose())
  }
}
