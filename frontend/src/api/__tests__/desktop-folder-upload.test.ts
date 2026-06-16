import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createFolderUploadPlanFromHandle,
  createFolderUploadPlanFromRelativeFiles,
  isFolderPickerCancel,
} from '@/api/desktopFolderUpload'

class TestFileHandle {
  kind = 'file' as const
  private file: File

  constructor(file: File) {
    this.file = file
  }

  async getFile() {
    return this.file
  }
}

class TestDirectoryHandle {
  kind = 'directory' as const
  entriesMap: Array<[string, TestDirectoryHandle | TestFileHandle]> = []
  name: string

  constructor(name: string) {
    this.name = name
  }

  addDirectory(directory: TestDirectoryHandle) {
    this.entriesMap.push([directory.name, directory])
    return directory
  }

  addFile(name: string, contents = 'x') {
    this.entriesMap.push([name, new TestFileHandle(new File([contents], name, { type: 'text/plain' }))])
  }

  async *entries() {
    yield* this.entriesMap
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('desktop folder upload', () => {
  it('collects directory paths and file entries from File System Access handles', async () => {
    const root = new TestDirectoryHandle('Project')
    const src = root.addDirectory(new TestDirectoryHandle('src'))
    src.addFile('main.ts')
    root.addDirectory(new TestDirectoryHandle('empty'))

    const plan = await createFolderUploadPlanFromHandle(root as unknown as FileSystemDirectoryHandle, null)

    expect(plan).toMatchObject({ rootName: 'Project', directoryCount: 3 })
    expect(plan.emptyDirectories).toEqual([['Project', 'empty']])
    expect(plan.files).toHaveLength(1)
    expect(plan.files[0]).toMatchObject({
      parentId: null,
      fileName: 'main.ts',
      displayName: 'Project/src/main.ts',
      directoryPath: ['Project', 'src'],
    })
  })

  it('builds a folder tree from webkitRelativePath files', async () => {
    const nested = new File(['x'], 'ignored.txt', { type: 'text/plain' })
    Object.defineProperty(nested, 'webkitRelativePath', { value: 'Photos/2026/a.txt' })
    const rootFile = new File(['x'], 'ignored.txt', { type: 'text/plain' })
    Object.defineProperty(rootFile, 'webkitRelativePath', { value: 'Photos/readme.txt' })

    const plan = await createFolderUploadPlanFromRelativeFiles([nested, rootFile], 10)

    expect(plan.files.map(file => file.displayName)).toEqual(['Photos/2026/a.txt', 'Photos/readme.txt'])
    expect(plan.files.map(file => file.parentId)).toEqual([10, 10])
    expect(plan.files.map(file => file.fileName)).toEqual(['a.txt', 'readme.txt'])
    expect(plan.files.map(file => file.directoryPath)).toEqual([['Photos', '2026'], ['Photos']])
  })

  it('recognizes folder picker cancellation errors', () => {
    expect(isFolderPickerCancel({ name: 'AbortError' })).toBe(true)
    expect(isFolderPickerCancel({ name: 'NotAllowedError' })).toBe(true)
    expect(isFolderPickerCancel(new Error('boom'))).toBe(false)
  })
})
