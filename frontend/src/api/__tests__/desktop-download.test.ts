import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as filesApi from '@/api/files'
import {
  buildDownloadUrl,
  buildUniquePathName,
  downloadDesktopFile,
  downloadDesktopFolder,
  isPickerAbort,
  listAllChildren,
  sanitizePathSegment,
} from '@/api/desktopDownload'
import type { FileEntity } from '@/types/file'
import { invoke } from '@tauri-apps/api/core'

vi.mock('@/api/files', () => ({
  listFiles: vi.fn(),
}))

vi.mock('@/config/runtime', () => ({
  getApiBaseUrl: () => 'http://127.0.0.1:8080/api/v1',
  isDesktopRuntime: () => true,
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}))

const listFiles = vi.mocked(filesApi.listFiles)
const invokeMock = vi.mocked(invoke)

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
      message: 'ok',
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
  beforeEach(() => {
    listFiles.mockReset()
    invokeMock.mockReset()
  })

  it('builds authenticated download URLs from the current API base', () => {
    expect(buildDownloadUrl(7)).toBe('http://127.0.0.1:8080/api/v1/download/7')
  })

  it('sanitizes local file and folder names', () => {
    expect(sanitizePathSegment('a<b>:c?.txt')).toBe('a_b__c_.txt')
    expect(sanitizePathSegment('...')).toBe('download')
    expect(buildUniquePathName('root.txt', 1)).toBe('root (1).txt')
    expect(buildUniquePathName('docs', 2)).toBe('docs (2)')
  })

  it('detects picker cancellation', () => {
    expect(isPickerAbort(new DOMException('cancel', 'AbortError'))).toBe(true)
    expect(isPickerAbort(new Error('boom'))).toBe(false)
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

  it('downloads one file after the user chooses a target path', async () => {
    invokeMock.mockResolvedValue({ saved: true, path: 'chosen/a_.txt' })

    await expect(downloadDesktopFile(3, 'a?.txt')).resolves.toBe(true)

    expect(invokeMock).toHaveBeenCalledWith('download_desktop_file', {
      url: 'http://127.0.0.1:8080/api/v1/download/3',
      fileName: 'a?.txt',
      token: undefined,
    })
  })

  it('recursively downloads folders without requesting ZIP files', async () => {
    listFiles.mockImplementation(async (params) => {
      if (params.parentId === 10) return page([folder(11, 10, 'child'), file(12, 10, 'root.txt')])
      if (params.parentId === 11) return page([file(13, 11, 'nested.txt')])
      return page([])
    })

    invokeMock.mockImplementation(async (command, args) => {
      if (command === 'choose_desktop_folder') return { selected: true, path: 'chosen/docs' }
      if (command === 'create_desktop_subdirectory') return { selected: true, path: `${(args as { directoryPath: string }).directoryPath}/child` }
      return { saved: true, path: 'downloaded' }
    })

    await expect(downloadDesktopFolder({ id: 10, fileName: 'docs' })).resolves.toBe(true)

    expect(invokeMock).toHaveBeenCalledWith('choose_desktop_folder', { folderName: 'docs' })
    expect(invokeMock).toHaveBeenCalledWith('create_desktop_subdirectory', {
      directoryPath: 'chosen/docs',
      folderName: 'child',
    })
    expect(invokeMock).toHaveBeenCalledWith('download_desktop_file_to_path', {
      url: 'http://127.0.0.1:8080/api/v1/download/12',
      fileName: 'root.txt',
      directoryPath: 'chosen/docs',
      token: undefined,
    })
    expect(invokeMock).toHaveBeenCalledWith('download_desktop_file_to_path', {
      url: 'http://127.0.0.1:8080/api/v1/download/13',
      fileName: 'nested.txt',
      directoryPath: 'chosen/docs/child',
      token: undefined,
    })
  })
})
