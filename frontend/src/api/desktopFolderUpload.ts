export interface FolderUploadFileEntry {
  file: File
  parentId: number | null
  fileName: string
  displayName: string
  directoryPath: string[]
}

export interface FolderUploadPlan {
  rootName: string
  files: FolderUploadFileEntry[]
  emptyDirectories: string[][]
  directoryCount: number
}

interface FolderNode {
  name: string
  directories: Map<string, FolderNode>
  files: File[]
}

interface BrowserWindowWithFolderPickers extends Window {
  showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle>
}

export const isFolderPickerCancel = (error: unknown) => {
  if (!error || typeof error !== 'object') return false
  const name = (error as { name?: string }).name
  return name === 'AbortError' || name === 'NotAllowedError'
}

export async function chooseFolderUploadPlan(targetParentId: number | null): Promise<FolderUploadPlan | null> {
  const win = window as BrowserWindowWithFolderPickers
  if (win.showDirectoryPicker) {
    const directory = await win.showDirectoryPicker()
    return createFolderUploadPlanFromHandle(directory, targetParentId)
  }
  return createFolderUploadPlanFromInput(targetParentId)
}

export async function createFolderUploadPlanFromHandle(
  root: FileSystemDirectoryHandle,
  targetParentId: number | null,
): Promise<FolderUploadPlan> {
  const files: FolderUploadFileEntry[] = []
  const emptyDirectories: string[][] = []
  const directoryCount = await collectHandleDirectory(root, targetParentId, [root.name], files, emptyDirectories)
  return { rootName: root.name, files, emptyDirectories, directoryCount: directoryCount + 1 }
}

export async function createFolderUploadPlanFromInput(targetParentId: number | null): Promise<FolderUploadPlan | null> {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.setAttribute('webkitdirectory', '')
  input.setAttribute('directory', '')

  const files = await new Promise<File[] | null>((resolve) => {
    input.onchange = () => resolve(input.files?.length ? Array.from(input.files) : null)
    input.oncancel = () => resolve(null)
    input.click()
  })
  if (!files?.length) return null

  return createFolderUploadPlanFromRelativeFiles(files, targetParentId)
}

export async function createFolderUploadPlanFromRelativeFiles(
  files: File[],
  targetParentId: number | null,
): Promise<FolderUploadPlan> {
  const root = buildFolderTree(files)
  const entries: FolderUploadFileEntry[] = []
  const emptyDirectories: string[][] = []
  const directoryCount = await collectTreeDirectory(root, targetParentId, [root.name], entries, emptyDirectories)
  return { rootName: root.name, files: entries, emptyDirectories, directoryCount: directoryCount + 1 }
}

async function collectHandleDirectory(
  directory: FileSystemDirectoryHandle,
  targetParentId: number | null,
  path: string[],
  files: FolderUploadFileEntry[],
  emptyDirectories: string[][],
) {
  let childDirectoryCount = 0
  let childCount = 0
  for await (const [name, handle] of directory.entries()) {
    childCount += 1
    if (handle.kind === 'directory') {
      childDirectoryCount += 1 + await collectHandleDirectory(handle, targetParentId, [...path, name], files, emptyDirectories)
    } else {
      const file = await handle.getFile()
      files.push({
        file,
        parentId: targetParentId,
        fileName: file.name,
        displayName: [...path, file.name].join('/'),
        directoryPath: path,
      })
    }
  }
  if (childCount === 0) emptyDirectories.push(path)
  return childDirectoryCount
}

async function collectTreeDirectory(
  directory: FolderNode,
  targetParentId: number | null,
  path: string[],
  files: FolderUploadFileEntry[],
  emptyDirectories: string[][],
) {
  let childDirectoryCount = 0
  for (const child of directory.directories.values()) {
    childDirectoryCount += 1 + await collectTreeDirectory(child, targetParentId, [...path, child.name], files, emptyDirectories)
  }
  for (const file of directory.files) {
    files.push({
      file,
      parentId: targetParentId,
      fileName: file.name,
      displayName: [...path, file.name].join('/'),
      directoryPath: path,
    })
  }
  if (directory.directories.size === 0 && directory.files.length === 0) emptyDirectories.push(path)
  return childDirectoryCount
}

function buildFolderTree(files: File[]) {
  const rootName = parseRelativePath(files[0])[0] || '文件夹'
  const root: FolderNode = { name: rootName, directories: new Map(), files: [] }

  for (const file of files) {
    const parts = parseRelativePath(file)
    const fileName = parts.pop() || file.name
    let current = root
    const directories = parts[0] === root.name ? parts.slice(1) : parts
    for (const directoryName of directories) {
      let child = current.directories.get(directoryName)
      if (!child) {
        child = { name: directoryName, directories: new Map(), files: [] }
        current.directories.set(directoryName, child)
      }
      current = child
    }
    current.files.push(renameFileForDisplay(file, fileName))
  }
  return root
}

function parseRelativePath(file: File) {
  const path = file.webkitRelativePath || file.name
  return path.split('/').filter(Boolean)
}

function renameFileForDisplay(file: File, fileName: string) {
  if (file.name === fileName) return file
  return new File([file], fileName, { type: file.type, lastModified: file.lastModified })
}
