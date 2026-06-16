import { ref } from 'vue'
import { useUploadStore } from '@/stores/upload'
import { useFilesStore } from '@/stores/files'
import { chooseFolderUploadPlan, isFolderPickerCancel, type FolderUploadPlan } from '@/api/desktopFolderUpload'
import {
  isDesktopFolderUploadRuntime,
  registerDesktopUploadListeners,
  uploadDesktopFolder,
} from '@/api/desktopUpload'
import * as filesApi from '@/api/files'
import { findResumableDirectory, saveResumableDirectory } from '@/api/resumableUpload'
import { toast } from 'vue-sonner'

const FILE_NAME_CONFLICT_CODE = 409001
let desktopUploadListenersReady = false

export function useUpload() {
  const uploadStore = useUploadStore()
  const filesStore = useFilesStore()
  const isDragging = ref(false)

  registerDesktopUploadBridge(uploadStore, filesStore)

  function onDragEnter(e: DragEvent) {
    if (e.dataTransfer?.types.includes('Files')) isDragging.value = true
  }

  function onDragLeave(e: DragEvent) {
    if (!(e.currentTarget as Element).contains(e.relatedTarget as Element)) {
      isDragging.value = false
    }
  }

  function onDragOver(e: DragEvent) {
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    isDragging.value = false
    const files = Array.from(e.dataTransfer?.files ?? [])
    if (files.length) uploadFiles(files)
  }

  function triggerFilePicker() {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    input.onchange = () => {
      if (input.files?.length) uploadFiles(Array.from(input.files))
    }
    input.click()
  }

  async function triggerFolderPicker() {
    try {
      if (isDesktopFolderUploadRuntime()) {
        const result = await uploadDesktopFolder(filesStore.currentParentId)
        if (result.selected && result.rootName) {
          toast.success(`${result.rootName} 已加入上传队列`)
        }
        return
      }

      const plan = await chooseFolderUploadPlan(filesStore.currentParentId)
      if (!plan) return
      const entries = await prepareFolderUploadEntries(plan, filesStore.currentParentId)
      uploadStore.addUploadFiles(entries)
      toast.success(`${plan.rootName} 已加入上传队列`)
      if (plan.directoryCount > 0 && entries.length === 0) {
        await filesStore.refresh()
      }
    } catch (error) {
      if (isFolderPickerCancel(error)) return
      const message = error instanceof Error ? error.message : '上传文件夹失败'
      toast.error(message)
    }
  }

  function uploadFiles(files: File[]) {
    uploadStore.addFiles(files, filesStore.currentParentId)
  }

  return {
    isDragging,
    onDragEnter,
    onDragLeave,
    onDragOver,
    onDrop,
    triggerFilePicker,
    triggerFolderPicker,
    uploadFiles,
  }
}

function registerDesktopUploadBridge(uploadStore: ReturnType<typeof useUploadStore>, filesStore: ReturnType<typeof useFilesStore>) {
  if (desktopUploadListenersReady || !isDesktopFolderUploadRuntime()) return
  desktopUploadListenersReady = true
  registerDesktopUploadListeners({
    onItemUpdated: (item) => {
      uploadStore.applyDesktopUploadItem({
        id: item.id,
        fileName: item.fileName,
        displayName: item.displayName,
        parentId: item.parentId,
        status: item.status,
        progress: item.progress,
        chunkProgress: item.chunkProgress,
        error: item.error ?? null,
        uploadId: item.uploadId ?? undefined,
        fileSize: item.fileSize,
        md5Hash: item.md5Hash ?? undefined,
        totalChunks: item.totalChunks,
        resumable: item.resumable,
        desktop: true,
      })
    },
    onBatchCompleted: () => {
      filesStore.refresh()
      toast.success('文件夹上传完成')
    },
    onError: (message) => {
      toast.error(message)
    },
  }).catch((error) => {
    desktopUploadListenersReady = false
    const message = error instanceof Error ? error.message : '初始化客户端上传失败'
    toast.error(message)
  })
}

async function prepareFolderUploadEntries(plan: FolderUploadPlan, baseParentId: number | null) {
  const directoryIds = new Map<string, number | null>()
  directoryIds.set(directoryPathKey([], baseParentId), baseParentId)
  const paths = collectDirectoryPaths(plan)
  for (const path of paths) {
    const key = directoryPathKey(path, baseParentId)
    const stored = findResumableDirectory(key)
    if (stored) {
      directoryIds.set(key, stored.remoteId)
      continue
    }

    const parentPath = path.slice(0, -1)
    const parentKey = directoryPathKey(parentPath, baseParentId)
    const parentId = directoryIds.get(parentKey) ?? baseParentId
    const remote = await createUniqueRemoteFolder(parentId, path[path.length - 1])
    directoryIds.set(key, remote.id)
    saveResumableDirectory({
      pathKey: key,
      parentPathKey: parentKey || null,
      baseParentId,
      remoteId: remote.id,
      remoteName: remote.fileName,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })
  }

  return plan.files.map(file => ({
    file: file.file,
    parentId: directoryIds.get(directoryPathKey(file.directoryPath, baseParentId)) ?? baseParentId,
    fileName: file.fileName,
    displayName: file.displayName,
  }))
}

function collectDirectoryPaths(plan: FolderUploadPlan) {
  const keys = new Map<string, string[]>()
  for (const file of plan.files) {
    for (let depth = 1; depth <= file.directoryPath.length; depth += 1) {
      const path = file.directoryPath.slice(0, depth)
      keys.set(path.join('/'), path)
    }
  }
  for (const directory of plan.emptyDirectories) {
    for (let depth = 1; depth <= directory.length; depth += 1) {
      const path = directory.slice(0, depth)
      keys.set(path.join('/'), path)
    }
  }
  return [...keys.values()]
}

async function createUniqueRemoteFolder(parentId: number | null, folderName: string) {
  let index = 0
  while (true) {
    const candidate = index === 0 ? folderName : `${folderName} (${index})`
    try {
      const { data } = await filesApi.createFolder(parentId, candidate)
      return data.data
    } catch (error) {
      const code = (error as { response?: { data?: { code?: number } } }).response?.data?.code
      if (code !== FILE_NAME_CONFLICT_CODE) throw error
      index += 1
    }
  }
}

function directoryPathKey(path: string[], baseParentId: number | null) {
  return `${baseParentId ?? 'root'}:${path.join('/')}`
}
