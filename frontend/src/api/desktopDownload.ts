import * as filesApi from './files'
import { ensureFreshAccessToken, refreshAccessToken } from './client'
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

interface DownloadTreeNode {
  id: number
  fileName: string
  fileType: 'file' | 'folder'
  fileSize: number
  children: DownloadTreeNode[]
}

export const isDesktopDownloadRuntime = () => isDesktopRuntime()

export function isPickerAbort(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function getBearerToken() {
  return localStorage.getItem('accessToken') || undefined
}

function isAuthExpiredError(error: unknown) {
  return String(error).includes('AUTH_EXPIRED')
}

async function getFreshBearerToken() {
  return ensureFreshAccessToken()
}

async function invokeDownloadWithToken<T>(
  command: string,
  buildArgs: (token: string | undefined) => Record<string, unknown>,
) {
  const token = await getFreshBearerToken()
  try {
    return await invoke<T>(command, buildArgs(token))
  } catch (error) {
    if (!isAuthExpiredError(error)) throw error
    const refreshedToken = await refreshAccessToken()
    return invoke<T>(command, buildArgs(refreshedToken))
  }
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
  const result = await invokeDownloadWithToken<DesktopDownloadResult>('download_desktop_file', (token) => ({
    url: buildDownloadUrl(fileId),
    fileName,
    token,
  }))
  return result.saved
}

export async function startQueuedDesktopFileDownload(file: Pick<FileEntity, 'id' | 'fileName' | 'fileSize'>) {
  const token = await getFreshBearerToken()
  const result = await invoke<DesktopDownloadResult>('start_desktop_download_file', {
    request: {
      fileId: file.id,
      fileName: file.fileName,
      fileSize: file.fileSize,
      token,
      apiBaseUrl: getApiBaseUrl(),
    },
  })
  return result.saved
}

export async function startQueuedDesktopFolderDownload(folder: Pick<FileEntity, 'id' | 'fileName' | 'fileSize'>) {
  const tree = await buildDownloadTree(folder.id, folder.fileName, 'folder', folder.fileSize)
  const token = await getFreshBearerToken()
  const result = await invoke<DesktopDownloadResult>('start_desktop_download_folder', {
    request: {
      root: tree,
      token,
      apiBaseUrl: getApiBaseUrl(),
    },
  })
  return result.saved
}

export async function downloadDesktopFolder(folder: Pick<FileEntity, 'id' | 'fileName'>) {
  const root = await invoke<DesktopFolderSelection>('choose_desktop_folder', { folderName: folder.fileName })
  if (!root.selected || !root.path) return false
  const tasks: DownloadTask[] = []
  await collectDownloadTasks(folder.id, root.path, tasks)
  await runPool(tasks, 3, async (task) => {
    await invokeDownloadWithToken<DesktopDownloadResult>('download_desktop_file_to_path', (token) => ({
      url: buildDownloadUrl(task.fileId),
      fileName: task.fileName,
      directoryPath: task.directoryPath,
      token,
    }))
  })
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

interface DownloadTask {
  fileId: number
  fileName: string
  directoryPath: string
}

async function collectDownloadTasks(parentId: number, directoryPath: string, tasks: DownloadTask[]) {
  const children = await listAllChildren(parentId)

  for (const child of children) {
    if (child.fileType === 'folder') {
      const childDirectory = await invoke<DesktopFolderSelection>('create_desktop_subdirectory', {
        directoryPath,
        folderName: child.fileName,
      })
      if (!childDirectory.selected || !childDirectory.path) continue
      await collectDownloadTasks(child.id, childDirectory.path, tasks)
      continue
    }
    tasks.push({ fileId: child.id, fileName: child.fileName, directoryPath })
  }
}

async function buildDownloadTree(
  id: number,
  fileName: string,
  fileType: 'file' | 'folder',
  fileSize: number,
): Promise<DownloadTreeNode> {
  if (fileType === 'file') {
    return { id, fileName, fileType, fileSize, children: [] }
  }

  const children = await listAllChildren(id)
  return {
    id,
    fileName,
    fileType,
    fileSize,
    children: await Promise.all(children.map(child =>
      buildDownloadTree(child.id, child.fileName, child.fileType, child.fileSize),
    )),
  }
}

async function runPool<T>(items: T[], limit: number, worker: (item: T) => Promise<void>) {
  const concurrency = Math.max(1, Math.min(16, Math.floor(limit || 1)))
  let next = 0
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (next < items.length) {
      const item = items[next++]
      await worker(item)
    }
  })
  await Promise.all(runners)
}
