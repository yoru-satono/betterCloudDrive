import { defineStore } from 'pinia'
import { ref } from 'vue'
import SparkMD5 from 'spark-md5'
import * as uploadApi from '@/api/upload'
import { useFilesStore } from './files'
import { toast } from 'vue-sonner'

export type UploadStatus = 'pending' | 'hashing' | 'uploading' | 'done' | 'error' | 'instant' | 'canceled'

export interface UploadItem {
  id: string
  file: File
  fileName: string
  status: UploadStatus
  progress: number
  chunkProgress: string
  error: string | null
  uploadId?: string
}

export const useUploadStore = defineStore('upload', () => {
  const queue = ref<UploadItem[]>([])
  const isOpen = ref(false)

  function addFiles(files: File[], parentId: number | null) {
    const items: UploadItem[] = files.map(f => ({
      id: crypto.randomUUID(),
      file: f,
      fileName: f.name,
      status: 'pending',
      progress: 0,
      chunkProgress: '',
      error: null
    }))
    queue.value.push(...items)
    isOpen.value = true
    items.forEach(item => processUpload(item, parentId))
  }

  function removeItem(id: string) {
    const idx = queue.value.findIndex(i => i.id === id)
    if (idx >= 0) queue.value.splice(idx, 1)
  }

  function clearDone() {
    queue.value = queue.value.filter(i => i.status !== 'done' && i.status !== 'instant')
  }

  async function processUpload(item: UploadItem, parentId: number | null) {
    const update = (patch: Partial<UploadItem>) => {
      const i = queue.value.find(q => q.id === item.id)
      if (i) Object.assign(i, normalizeUploadPatch(patch))
    }

    try {
      update({ status: 'hashing' })
      const md5 = await computeMd5(item.file, (pct) => update({ progress: pct * 30 }))
      const CHUNK_SIZE = 5 * 1024 * 1024
      const totalChunks = Math.max(1, Math.ceil(item.file.size / CHUNK_SIZE))

      const instant = await tryInstantUpload(parentId, item.fileName, item.file.size, md5, item.file.type || undefined)
      if (instant) {
        update({ status: 'instant', progress: 100 })
        toast.success(`${item.fileName} 秒传成功`)
        useFilesStore().refresh()
        return
      }

      update({ status: 'uploading', progress: 30 })

      const initRes = await uploadApi.initMultipart(parentId, item.fileName, item.file.size, md5, totalChunks, item.file.type || undefined)
      const uploadId = initRes.data.data.sessionId
      update({ uploadId })
      await refreshUploadStatus(item.id).catch(() => {})

      for (let i = 0; i < totalChunks; i++) {
        const current = queue.value.find(q => q.id === item.id)
        if (current?.status === 'canceled') return
        const chunk = item.file.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)
        await uploadApi.uploadChunk(uploadId, i, chunk, (pct) => {
          const overall = 30 + ((i + pct / 100) / totalChunks) * 65
          update({ progress: overall, chunkProgress: `${i + 1}/${totalChunks}` })
        })
      }

      await uploadApi.completeMultipart(uploadId)
      update({ status: 'done', progress: 100, chunkProgress: '' })
      toast.success(`${item.fileName} 上传完成`)
      useFilesStore().refresh()
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '上传失败'
      update({ status: 'error', error: msg })
      toast.error(`${item.fileName} 上传失败`)
    }
  }

  async function cancelUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    if (item.uploadId) {
      await uploadApi.abortMultipart(item.uploadId).catch(() => {})
    }
    Object.assign(item, { status: 'canceled' as UploadStatus, error: null })
  }

  async function refreshUploadStatus(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item?.uploadId) return
    const { data } = await uploadApi.getUploadStatus(item.uploadId)
    const status = data.data
    if (status.uploadedChunks !== undefined && status.totalChunks) {
      item.chunkProgress = `${status.uploadedChunks}/${status.totalChunks}`
    }
  }

  return { queue, isOpen, addFiles, removeItem, clearDone, cancelUpload, refreshUploadStatus }
})

function normalizeUploadPatch(patch: Partial<UploadItem>) {
  if (patch.progress === undefined) {
    return patch
  }
  return {
    ...patch,
    progress: normalizeProgress(patch.progress)
  }
}

function normalizeProgress(progress: number) {
  const clamped = Math.min(100, Math.max(0, progress))
  return Math.round(clamped * 100) / 100
}

async function tryInstantUpload(parentId: number | null, fileName: string, fileSize: number, md5: string, mimeType?: string) {
  try {
    const res = await uploadApi.instantUpload(parentId, fileName, fileSize, md5, mimeType)
    return res.data.data?.instant === true
  } catch (e: unknown) {
    const code = (e as { response?: { data?: { code?: number } } }).response?.data?.code
    if (code === 419010) return false
    throw e
  }
}

function computeMd5(file: File, onProgress?: (pct: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const CHUNK = 2 * 1024 * 1024
    const chunks = Math.max(1, Math.ceil(file.size / CHUNK))
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    let current = 0

    const loadNext = () => {
      const start = current * CHUNK
      const end = Math.min(start + CHUNK, file.size)
      reader.readAsArrayBuffer(file.slice(start, end))
    }

    reader.onload = (e) => {
      spark.append(e.target!.result as ArrayBuffer)
      current++
      onProgress?.(current / chunks)
      if (current < chunks) loadNext()
      else resolve(spark.end())
    }
    reader.onerror = () => reject(reader.error)
    loadNext()
  })
}
