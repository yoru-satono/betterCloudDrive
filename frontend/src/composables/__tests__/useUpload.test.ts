import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFilesStore } from '@/stores/files'
import { useUploadStore } from '@/stores/upload'
import { useUpload } from '@/composables/useUpload'
import * as folderUpload from '@/api/desktopFolderUpload'
import * as filesApi from '@/api/files'
import { saveResumableDirectory, createResumableScope, readResumableDirectories } from '@/api/resumableUpload'
import { invalidateIdentity } from '@/platform/identity'

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
  invalidateIdentity('test')
  setActivePinia(createPinia())
  localStorage.clear()
  chooseFolderUploadPlan.mockReset()
  createFolder.mockReset()
  localStorage.setItem('accessToken', 'synthetic-access')
  useUploadStore().activateIdentity('/api/v1', 7)
})

describe('useUpload', () => {
  it('stops directory creation and cache writes when a response arrives after logout', async () => {
    let finish!: (value: unknown) => void
    createFolder.mockReturnValueOnce(new Promise(resolve => { finish=resolve }))
    chooseFolderUploadPlan.mockResolvedValueOnce({rootName:'private',directoryCount:2,emptyDirectories:[['private','nested']],files:[]})
    const store=useUploadStore(),add=vi.spyOn(store,'addUploadFiles')
    const uploading=useUpload().triggerFolderPicker()
    await vi.waitFor(()=>expect(createFolder).toHaveBeenCalledTimes(1))
    invalidateIdentity('logout')
    finish({data:{data:{id:21,fileName:'private'}}});await uploading
    expect(createFolder).toHaveBeenCalledTimes(1)
    expect(readResumableDirectories(createResumableScope('/api/v1',7))).toEqual([])
    expect(add).not.toHaveBeenCalled()
  })

  it('does not create directories when a picker returns after identity changes', async () => {
    let finish!: (value: unknown) => void
    chooseFolderUploadPlan.mockReturnValueOnce(new Promise(resolve => { finish=resolve }))
    const uploading=useUpload().triggerFolderPicker()
    invalidateIdentity('connection')
    finish({rootName:'old',directoryCount:1,emptyDirectories:[['old']],files:[]});await uploading
    expect(createFolder).not.toHaveBeenCalled()
  })
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
    saveResumableDirectory(createResumableScope('/api/v1',7), {
      pathKey: 'root:Project',
      parentPathKey: 'root:',
      baseParentId: null,
      remoteId: 21,
      remoteName: 'Project',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })
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
