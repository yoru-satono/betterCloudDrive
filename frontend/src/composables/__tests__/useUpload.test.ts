import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFilesStore } from '@/stores/files'
import { useUploadStore } from '@/stores/upload'
import { useUpload } from '@/composables/useUpload'
import * as folderUpload from '@/api/desktopFolderUpload'
import * as filesApi from '@/api/files'

vi.mock('@/api/desktopFolderUpload', () => ({
  chooseFolderUploadPlan: vi.fn(),
  isFolderPickerCancel: vi.fn(() => false),
}))

vi.mock('@/api/files', () => ({
  createFolder: vi.fn(),
}))

const chooseFolderUploadPlan = folderUpload.chooseFolderUploadPlan as Mock
const createFolder = filesApi.createFolder as Mock

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  chooseFolderUploadPlan.mockReset()
  createFolder.mockReset()
})

describe('useUpload', () => {
  it('adds files to the upload store at root with null parent id', () => {
    const filesStore = useFilesStore()
    filesStore.currentParentId = null
    const uploadStore = useUploadStore()
    const addFiles = vi.spyOn(uploadStore, 'addFiles').mockImplementation(() => {})
    const files = [new File(['x'], 'root.txt')]

    useUpload().uploadFiles(files)

    expect(addFiles).toHaveBeenCalledWith(files, null)
  })

  it('adds files to the upload store when inside a folder', () => {
    const filesStore = useFilesStore()
    filesStore.currentParentId = 10
    const uploadStore = useUploadStore()
    const addFiles = vi.spyOn(uploadStore, 'addFiles').mockImplementation(() => {})
    const files = [new File(['x'], 'folder.txt')]

    useUpload().uploadFiles(files)

    expect(addFiles).toHaveBeenCalledWith(files, 10)
  })

  it('creates remote folder paths before adding folder upload files', async () => {
    const filesStore = useFilesStore()
    filesStore.currentParentId = null
    const uploadStore = useUploadStore()
    const addUploadFiles = vi.spyOn(uploadStore, 'addUploadFiles').mockImplementation(() => {})
    createFolder
      .mockResolvedValueOnce({ data: { data: { id: 11, fileName: 'Project' } } })
      .mockResolvedValueOnce({ data: { data: { id: 12, fileName: 'src' } } })
    const file = new File(['x'], 'main.ts', { type: 'text/plain' })
    chooseFolderUploadPlan.mockResolvedValue({
      rootName: 'Project',
      directoryCount: 2,
      emptyDirectories: [],
      files: [{
        file,
        parentId: null,
        fileName: 'main.ts',
        displayName: 'Project/src/main.ts',
        directoryPath: ['Project', 'src'],
      }],
    })

    await useUpload().triggerFolderPicker()

    expect(createFolder).toHaveBeenNthCalledWith(1, null, 'Project')
    expect(createFolder).toHaveBeenNthCalledWith(2, 11, 'src')
    expect(addUploadFiles).toHaveBeenCalledWith([{
      file,
      parentId: 12,
      fileName: 'main.ts',
      displayName: 'Project/src/main.ts',
    }])
  })

  it('reuses persisted remote folder paths for folder upload resume', async () => {
    localStorage.setItem('bcd.resumableUploadDirectories', JSON.stringify([{
      pathKey: 'root:Project',
      parentPathKey: 'root:',
      baseParentId: null,
      remoteId: 21,
      remoteName: 'Project',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }]))
    const uploadStore = useUploadStore()
    const addUploadFiles = vi.spyOn(uploadStore, 'addUploadFiles').mockImplementation(() => {})
    const file = new File(['x'], 'readme.txt')
    chooseFolderUploadPlan.mockResolvedValue({
      rootName: 'Project',
      directoryCount: 1,
      emptyDirectories: [],
      files: [{
        file,
        parentId: null,
        fileName: 'readme.txt',
        displayName: 'Project/readme.txt',
        directoryPath: ['Project'],
      }],
    })

    await useUpload().triggerFolderPicker()

    expect(createFolder).not.toHaveBeenCalled()
    expect(addUploadFiles).toHaveBeenCalledWith([{
      file,
      parentId: 21,
      fileName: 'readme.txt',
      displayName: 'Project/readme.txt',
    }])
  })
})
