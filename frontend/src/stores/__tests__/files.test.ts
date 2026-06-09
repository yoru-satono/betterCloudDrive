import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFilesStore } from '@/stores/files'
import api from '@/api/client'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } }
  }
}))

const mockFile = (overrides = {}) => ({
  id: 1, fileName: 'test.txt', fileType: 'file', mimeType: 'text/plain',
  fileSize: 1024, isDeleted: false, versionCount: 1,
  createdAt: '2026-06-09T10:00:00', updatedAt: '2026-06-09T10:00:00', ...overrides
})

describe('Files Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchFiles should populate files and pagination', async () => {
    (api.get as any).mockResolvedValueOnce({
      data: { data: { records: [mockFile(), mockFile({ id: 2, fileName: 'b.txt' })], total: 2, page: 1, size: 20, pages: 1 } }
    })
    const store = useFilesStore()
    await store.fetchFiles(null)
    expect(store.files).toHaveLength(2)
    expect(store.total).toBe(2)
    expect(store.pages).toBe(1)
  })

  it('createFolder should call API and refresh', async () => {
    (api.post as any).mockResolvedValueOnce({ data: {} })
    ;(api.get as any).mockResolvedValueOnce({
      data: { data: { records: [], total: 0, page: 1, size: 20, pages: 0 } }
    })
    const store = useFilesStore()
    await store.createFolder(null, 'NewFolder')
    expect(api.post).toHaveBeenCalledWith('/files/folder', { parentId: null, folderName: 'NewFolder' })
  })

  it('deleteFiles should call API and refresh', async () => {
    (api.delete as any).mockResolvedValueOnce({ data: {} })
    ;(api.get as any).mockResolvedValueOnce({
      data: { data: { records: [], total: 0, page: 1, size: 20, pages: 0 } }
    })
    const store = useFilesStore()
    await store.deleteFiles([1, 2])
    expect(api.delete).toHaveBeenCalledWith('/files', { data: { fileIds: [1, 2] } })
  })

  it('renameFile should call PUT and refresh', async () => {
    (api.put as any).mockResolvedValueOnce({ data: {} })
    ;(api.get as any).mockResolvedValueOnce({
      data: { data: { records: [], total: 0, page: 1, size: 20, pages: 0 } }
    })
    const store = useFilesStore()
    await store.renameFile(1, 'renamed.txt')
    expect(api.put).toHaveBeenCalledWith('/files/1', { newName: 'renamed.txt' })
  })
})
