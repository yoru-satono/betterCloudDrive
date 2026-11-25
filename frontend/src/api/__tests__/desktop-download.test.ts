import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as filesApi from '@/api/files'
import { ensureFreshAccessToken } from '@/api/client'
import {
  listAllChildren,
  startQueuedDesktopFileDownload,
  startQueuedDesktopFolderDownload,
} from '@/api/desktopDownload'
import type { FileEntity } from '@/types/file'
import { invoke } from '@/platform/desktop'

vi.mock('@/api/files', () => ({
  listFiles: vi.fn(),
}))
vi.mock('@/api/teams', () => ({ listTeamFiles: vi.fn() }))

vi.mock('@/api/client', () => ({
  ensureFreshAccessToken: vi.fn(() => Promise.resolve(undefined)),
}))

vi.mock('@/config/runtime', () => ({
  getApiBaseUrl: () => 'http://127.0.0.1:8080/api/v1',
  isDesktopRuntime: () => true,
}))

vi.mock('@/platform/desktop', () => ({
  invoke: vi.fn(),
}))

vi.mock('@/api/desktopSettings', () => ({
  getDesktopSettings: vi.fn(() => Promise.resolve({ maxConcurrentDownloads: 3 })),
}))

const listFiles = vi.mocked(filesApi.listFiles)
const invokeMock = vi.mocked(invoke)
const ensureFreshAccessTokenMock = vi.mocked(ensureFreshAccessToken)

function file(id: number, parentId: number | null, fileName: string): FileEntity {
  return {
    id,
    userId: 1,
    parentId,
    fileName,
    fileType: 'file',
    mimeType: 'text/plain',
    fileSize: 1,
    storagePath: `objects/${id}`,
    md5Hash: null,
    isDeleted: false,
    versionCount: 1,
    createdAt: '',
    updatedAt: '',
  }
}

function folder(id: number, parentId: number | null, fileName: string): FileEntity {
  return {
    ...file(id, parentId, fileName),
    fileType: 'folder',
    fileSize: 0,
    storagePath: null,
  }
}

function page(records: FileEntity[], page = 1, pages = 1) {
  return Promise.resolve({
    data: {
      code: 200,
      messageKey: 'common.success',
      messageParams: {},
      data: {
        records,
        total: records.length,
        page,
        size: 100,
        pages,
      },
    },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} },
  } as Awaited<ReturnType<typeof filesApi.listFiles>>)
}

describe('desktop downloads', () => {
  it('uses only the queued command for a team file and preserves picker cancellation', async () => {
    invokeMock.mockResolvedValueOnce({saved:false})
    await expect(startQueuedDesktopFileDownload({id:3,fileName:'a.txt',fileSize:8},{teamId:9})).resolves.toBe(false)
    expect(invokeMock).toHaveBeenCalledWith('start_desktop_download_file',expect.objectContaining({request:expect.objectContaining({fileId:3,apiBaseUrl:'http://127.0.0.1:8080/api/v1/teams/9'})}))
  })

  it('builds team folder trees through team APIs and forwards native rejection', async () => {
    const {listTeamFiles}=await import('@/api/teams')
    vi.mocked(listTeamFiles).mockResolvedValueOnce(await page([file(12,10,'root.txt')]))
    invokeMock.mockRejectedValueOnce(new Error('PERMISSION_DENIED'))
    await expect(startQueuedDesktopFolderDownload({id:10,fileName:'docs',fileSize:0},{teamId:9})).rejects.toThrow('PERMISSION_DENIED')
    expect(listTeamFiles).toHaveBeenCalledWith(9,expect.objectContaining({parentId:10}))
    expect(invokeMock).toHaveBeenCalledWith('start_desktop_download_folder',expect.objectContaining({request:expect.objectContaining({apiBaseUrl:'http://127.0.0.1:8080/api/v1/teams/9',root:expect.objectContaining({id:10,children:[expect.objectContaining({id:12})]})})}))
  })
  beforeEach(() => {
    listFiles.mockReset()
    invokeMock.mockReset()
    ensureFreshAccessTokenMock.mockReset()
    ensureFreshAccessTokenMock.mockResolvedValue(undefined)
  })

  it('lists all children across pages', async () => {
    listFiles
      .mockResolvedValueOnce(await page([file(1, 9, 'a.txt')], 1, 2))
      .mockResolvedValueOnce(await page([file(2, 9, 'b.txt')], 2, 2))

    await expect(listAllChildren(9)).resolves.toMatchObject([
      { fileName: 'a.txt' },
      { fileName: 'b.txt' },
    ])
    expect(listFiles).toHaveBeenCalledTimes(2)
  })

  it('starts one queued desktop file download', async () => {
    ensureFreshAccessTokenMock.mockResolvedValue('fresh-token')
    invokeMock.mockResolvedValue({ saved: true, path: 'queued/a.txt' })

    await expect(startQueuedDesktopFileDownload({ id: 3, fileName: 'a.txt', fileSize: 42 })).resolves.toBe(true)

    expect(invokeMock).toHaveBeenCalledWith('start_desktop_download_file', {
      request: {
        fileId: 3,
        fileName: 'a.txt',
        fileSize: 42,
        token: 'fresh-token',
        apiBaseUrl: 'http://127.0.0.1:8080/api/v1',
      },
    })
  })

  it('starts a queued folder download with a recursive tree', async () => {
    ensureFreshAccessTokenMock.mockResolvedValue('fresh-token')
    listFiles.mockImplementation(async (params) => {
      if (params.parentId === 10) return page([folder(11, 10, 'child'), file(12, 10, 'root.txt')])
      if (params.parentId === 11) return page([file(13, 11, 'nested.txt')])
      return page([])
    })
    invokeMock.mockResolvedValue({ saved: true, path: 'queued/docs' })

    await expect(startQueuedDesktopFolderDownload({ id: 10, fileName: 'docs', fileSize: 0 })).resolves.toBe(true)

    expect(invokeMock).toHaveBeenCalledWith('start_desktop_download_folder', {
      request: {
        root: {
          id: 10,
          fileName: 'docs',
          fileType: 'folder',
          fileSize: 0,
          children: [
            {
              id: 11,
              fileName: 'child',
              fileType: 'folder',
              fileSize: 0,
              children: [
                { id: 13, fileName: 'nested.txt', fileType: 'file', fileSize: 1, children: [] },
              ],
            },
            { id: 12, fileName: 'root.txt', fileType: 'file', fileSize: 1, children: [] },
          ],
        },
        token: 'fresh-token',
        apiBaseUrl: 'http://127.0.0.1:8080/api/v1',
      },
    })
  })
})
