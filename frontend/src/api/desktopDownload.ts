import * as filesApi from './files'
import { getApiBaseUrl, isDesktopRuntime } from '@/config/runtime'
import type { FileEntity } from '@/types/file'
import { invoke } from '@tauri-apps/api/core'

const PAGE_SIZE = 100

interface DesktopDownloadResult {
  saved: boolean
  path?: string
}

interface DesktopFolderSelection {
  selected: boolean
  path?: string
}

export const isDesktopDownloadRuntime = () => isDesktopRuntime()

export function isPickerAbort(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function getBearerToken() {
  return localStorage.getItem('accessToken') || undefined
}

export function buildDownloadUrl(fileId: number) {
  return `${getApiBaseUrl()}/download/${fileId}`
}

export function sanitizePathSegment(value: string) {
  const sanitized = value
    .replace(/[<>:"/\\|?*\u0000-\u001F]/g, '_')
    .trim()
    .replace(/^\.+|\.+$/g, '')
  return sanitized || 'download'
}

export function buildUniquePathName(fileName: string, index: number) {
  const sanitized = sanitizePathSegment(fileName)
  if (index === 0) return sanitized

  const lastDot = sanitized.lastIndexOf('.')
  if (lastDot <= 0) return `${sanitized} (${index})`

  return `${sanitized.slice(0, lastDot)} (${index})${sanitized.slice(lastDot)}`
}

export async function downloadDesktopFile(fileId: number, fileName: string) {
  const result = await invoke<DesktopDownloadResult>('download_desktop_file', {
    url: buildDownloadUrl(fileId),
    fileName,
    token: getBearerToken(),
  })
  return result.saved
}

export async function downloadDesktopFolder(folder: Pick<FileEntity, 'id' | 'fileName'>) {
  const root = await invoke<DesktopFolderSelection>('choose_desktop_folder', { folderName: folder.fileName })
  if (!root.selected || !root.path) return false
  await downloadFolderChildren(folder.id, root.path)
  return true
}

export async function listAllChildren(parentId: number): Promise<FileEntity[]> {
  const records: FileEntity[] = []
  let page = 1
  let pages = 1

  do {
    const { data } = await filesApi.listFiles({
      parentId,
      page,
      size: PAGE_SIZE,
      sortBy: 'fileName',
      order: 'asc',
    })
    records.push(...data.data.records)
    pages = data.data.pages || 1
    page += 1
  } while (page <= pages)

  return records
}

async function downloadFolderChildren(parentId: number, directoryPath: string) {
  const children = await listAllChildren(parentId)

  for (const child of children) {
    if (child.fileType === 'folder') {
      const childDirectory = await invoke<DesktopFolderSelection>('create_desktop_subdirectory', {
        directoryPath,
        folderName: child.fileName,
      })
      if (!childDirectory.selected || !childDirectory.path) continue
      await downloadFolderChildren(child.id, childDirectory.path)
    } else {
      await invoke<DesktopDownloadResult>('download_desktop_file_to_path', {
        url: buildDownloadUrl(child.id),
        fileName: child.fileName,
        directoryPath,
        token: getBearerToken(),
      })
    }
  }
}
