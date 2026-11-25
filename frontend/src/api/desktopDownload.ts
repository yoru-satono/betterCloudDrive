import * as filesApi from './files'
import { ensureFreshAccessToken } from './client'
import { getApiBaseUrl, isDesktopRuntime } from '@/config/runtime'
import type { FileEntity } from '@/types/file'
import { invoke } from '@/platform/desktop'

const PAGE_SIZE = 100

interface DesktopDownloadResult {
  saved: boolean
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

export interface DesktopDownloadContext {
  teamId?: number
}

export async function startQueuedDesktopFileDownload(file: Pick<FileEntity, 'id' | 'fileName' | 'fileSize'>, context?: DesktopDownloadContext) {
  const token = await ensureFreshAccessToken()
  const result = await invoke<DesktopDownloadResult>('start_desktop_download_file', {
    request: {
      fileId: file.id,
      fileName: file.fileName,
      fileSize: file.fileSize,
      token,
      apiBaseUrl: context?.teamId ? `${getApiBaseUrl()}/teams/${context.teamId}` : getApiBaseUrl(),
    },
  })
  return result.saved
}

export async function startQueuedDesktopFolderDownload(folder: Pick<FileEntity, 'id' | 'fileName' | 'fileSize'>, context?: DesktopDownloadContext) {
  const tree = await buildDownloadTree(folder.id, folder.fileName, 'folder', folder.fileSize, context)
  const token = await ensureFreshAccessToken()
  const result = await invoke<DesktopDownloadResult>('start_desktop_download_folder', {
    request: {
      root: tree,
      token,
      apiBaseUrl: context?.teamId ? `${getApiBaseUrl()}/teams/${context.teamId}` : getApiBaseUrl(),
    },
  })
  return result.saved
}

export async function listAllChildren(parentId: number, context?: DesktopDownloadContext): Promise<FileEntity[]> {
  const records: FileEntity[] = []
  let page = 1
  let pages = 1

  do {
    const { data } = context?.teamId
      ? await (await import('./teams')).listTeamFiles(context.teamId, {
          parentId,
          page,
          size: PAGE_SIZE,
          sortBy: 'fileName',
          order: 'asc',
        })
      : await filesApi.listFiles({
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

async function buildDownloadTree(
  id: number,
  fileName: string,
  fileType: 'file' | 'folder',
  fileSize: number,
  context?: DesktopDownloadContext,
): Promise<DownloadTreeNode> {
  if (fileType === 'file') {
    return { id, fileName, fileType, fileSize, children: [] }
  }

  const children = await listAllChildren(id, context)
  return {
    id,
    fileName,
    fileType,
    fileSize,
    children: await Promise.all(children.map(child =>
      buildDownloadTree(child.id, child.fileName, child.fileType, child.fileSize, context),
    )),
  }
}
