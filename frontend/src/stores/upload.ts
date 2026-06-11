import { defineStore } from 'pinia'
import { ref } from 'vue'
import SparkMD5 from 'spark-md5'
import * as uploadApi from '@/api/upload'
import { useFilesStore } from './files'
import { toast } from 'vue-sonner'

export type UploadStatus = 'pending' | 'hashing' | 'uploading' | 'done' | 'error' | 'instant'

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
      if (i) Object.assign(i, patch)
    }

    try {
      update({ status: 'hashing' })
      const md5 = await computeMd5(item.file, (pct) => update({ progress: pct * 0.3 }))

      const instantRes = await uploadApi.instantUpload(parentId, item.fileName, item.file.size, md5, item.file.type || undefined)
      if (instantRes.data.data.existed) {
        update({ status: 'instant', progress: 100 })
        toast.success(`${item.fileName} 秒传成功`)
        useFilesStore().refresh()
        return
      }

      update({ status: 'uploading', progress: 30 })
      const CHUNK_SIZE = 5 * 1024 * 1024
      const totalChunks = Math.max(1, Math.ceil(item.file.size / CHUNK_SIZE))

      const initRes = await uploadApi.initMultipart(parentId, item.fileName, item.file.size, item.file.type || undefined)
      const uploadId = initRes.data.data.uploadId
      update({ uploadId })

      for (let i = 0; i < totalChunks; i++) {
        const chunk = item.file.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)
        await uploadApi.uploadChunk(uploadId, i, chunk, (pct) => {
          const overall = 30 + Math.round(((i + pct / 100) / totalChunks) * 65)
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

  return { queue, isOpen, addFiles, removeItem, clearDone }
})

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
