import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFilesStore } from '@/stores/files'
import * as filesApi from '@/api/files'
import type { FileEntity } from '@/types/file'

vi.mock('@/api/files', () => ({
  listFiles: vi.fn(),
  getFile: vi.fn(),
  searchFiles: vi.fn(),
}))

const listFiles = filesApi.listFiles as Mock
const getFile = filesApi.getFile as Mock
const searchFiles = filesApi.searchFiles as Mock

const file = (id: number, fileName: string): FileEntity => ({
  id,
  userId: 1,
  parentId: null,
  fileName,
  fileType: 'file',
  mimeType: 'text/plain',
  fileSize: 1,
  storagePath: null,
  md5Hash: null,
  isDeleted: false,
  versionCount: 1,
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-01T00:00:00',
})

beforeEach(() => {
  setActivePinia(createPinia())
  listFiles.mockReset()
  getFile.mockReset()
  searchFiles.mockReset()
})

describe('files store', () => {
  it('keeps search results separate from the current file list', async () => {
    const currentFiles = [file(1, 'all.txt'), file(2, 'docs.txt')]
    const results = [file(3, 'match.txt')]
    listFiles.mockResolvedValue({
      data: { data: { records: currentFiles, total: 2, page: 1, pages: 1 } },
    })
    searchFiles.mockResolvedValue({
      data: { data: { records: results, total: 1, page: 1, pages: 1 } },
    })

    const store = useFilesStore()
    await store.fetchFiles(null)
    await store.searchFiles('match')

    expect(store.files).toEqual(currentFiles)
    expect(store.searchResults).toEqual(results)
    expect(store.total).toBe(2)
  })

  it('opens the parent folder for a search result location', async () => {
    const target = file(7, 'match.txt')
    target.parentId = 42
    const parent = file(42, 'parent')
    parent.fileType = 'folder'
    const siblings = [target, file(8, 'sibling.txt')]
    getFile.mockResolvedValue({ data: { data: parent } })
    listFiles.mockResolvedValue({
      data: { data: { records: siblings, total: 2, page: 1, pages: 1 } },
    })

    const store = useFilesStore()
    await store.openLocationFor(target)

    expect(listFiles).toHaveBeenCalledWith(expect.objectContaining({ parentId: 42 }))
    expect(store.currentParentId).toBe(42)
    expect(store.files).toEqual(siblings)
    expect(store.breadcrumb).toEqual([
      { id: null, name: '全部文件' },
      { id: 42, name: 'parent' },
    ])
  })

  it('opens a nested directory and rebuilds breadcrumb from parents', async () => {
    const child = file(42, 'child')
    child.fileType = 'folder'
    child.parentId = 9
    const parent = file(9, 'parent')
    parent.fileType = 'folder'
    const children = [file(10, 'inside.txt')]
    getFile
      .mockResolvedValueOnce({ data: { data: child } })
      .mockResolvedValueOnce({ data: { data: parent } })
    listFiles.mockResolvedValue({
      data: { data: { records: children, total: 1, page: 1, pages: 1 } },
    })

    const store = useFilesStore()
    await store.openDirectory(42)

    expect(getFile).toHaveBeenNthCalledWith(1, 42)
    expect(getFile).toHaveBeenNthCalledWith(2, 9)
    expect(listFiles).toHaveBeenCalledWith(expect.objectContaining({ parentId: 42 }))
    expect(store.currentParentId).toBe(42)
    expect(store.breadcrumb).toEqual([
      { id: null, name: '全部文件' },
      { id: 9, name: 'parent' },
      { id: 42, name: 'child' },
    ])
  })
})
