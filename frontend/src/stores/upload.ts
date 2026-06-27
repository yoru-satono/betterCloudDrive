import { defineStore } from 'pinia'
import { ref } from 'vue'
import SparkMD5 from 'spark-md5'
import * as uploadApi from '@/api/upload'
import { cancelDesktopUpload } from '@/api/desktopUpload'
import {
  findResumableUpload,
  removeResumableUpload,
  readResumableUploads,
  saveResumableUpload,
  type ResumableUploadRecord,
} from '@/api/resumableUpload'
import { useFilesStore } from './files'
import { toast } from 'vue-sonner'
import { getDesktopSettings, isDesktopSettingsRuntime } from '@/api/desktopSettings'

export type UploadStatus = 'pending' | 'hashing' | 'uploading' | 'paused' | 'done' | 'error' | 'instant' | 'canceled' | 'resume_required'

const CHUNK_SIZE = 5 * 1024 * 1024
const DEFAULT_MAX_CONCURRENT_UPLOADS = 3

export interface UploadItem {
  id: string
  file?: File
  fileName: string
  displayName: string
  parentId: number | null
  status: UploadStatus
  progress: number
  chunkProgress: string
  error: string | null
  uploadId?: string
  fileSize?: number
  md5Hash?: string
  totalChunks?: number
  resumable?: boolean
  desktop?: boolean
  started?: boolean
  pausedFrom?: Exclude<UploadStatus, 'paused'>
}

export interface UploadFileEntry {
  file: File
  parentId: number | null
  fileName?: string
  displayName?: string
}

export const useUploadStore = defineStore('upload', () => {
  const queue = ref<UploadItem[]>([])
  const isOpen = ref(false)
  let scheduling = false

  function addFiles(files: File[], parentId: number | null) {
    addUploadFiles(files.map(file => ({ file, parentId })))
  }

  function addUploadFiles(entries: UploadFileEntry[]) {
    if (!entries.length) return
    const items: UploadItem[] = entries.map(entry => ({
      id: crypto.randomUUID(),
      file: entry.file,
      fileName: entry.fileName || entry.file.name,
      displayName: entry.displayName || entry.fileName || entry.file.name,
      parentId: entry.parentId,
      status: 'pending',
      progress: 0,
      chunkProgress: '',
      error: null
    }))
    queue.value.push(...items)
    isOpen.value = true
    scheduleUploads()
  }

  function restoreResumableUploads() {
    const records = readResumableUploads()
    if (!records.length || queue.value.length) return
    queue.value = records.map(record => ({
      id: crypto.randomUUID(),
      fileName: record.fileName,
      displayName: record.displayName,
      parentId: record.parentId,
      status: 'resume_required',
      progress: 0,
      chunkProgress: '',
      error: '请重新选择此文件以继续上传',
      uploadId: record.sessionId,
      fileSize: record.fileSize,
      md5Hash: record.md5Hash,
      totalChunks: record.totalChunks,
      resumable: true,
    }))
    isOpen.value = true
    queue.value.forEach(item => refreshUploadStatus(item.id).catch(() => {
      if (item.uploadId) removeResumableUpload(item.uploadId)
      Object.assign(item, {
        status: 'error' as UploadStatus,
        error: '续传会话已失效，请重新上传',
        resumable: false,
      })
    }))
  }

  function removeItem(id: string) {
    const idx = queue.value.findIndex(i => i.id === id)
    if (idx >= 0) queue.value.splice(idx, 1)
  }

  function clearDone() {
    queue.value = queue.value.filter(i => i.status !== 'done' && i.status !== 'instant')
  }

  function applyDesktopUploadItem(item: Omit<UploadItem, 'file'>) {
    const existing = queue.value.find(entry => entry.id === item.id)
    const next = normalizeUploadPatch({ ...item, desktop: true })
    if (existing) {
      Object.assign(existing, next)
    } else {
      queue.value.push({
        ...next,
        error: next.error ?? null,
        desktop: true,
      } as UploadItem)
    }
    isOpen.value = true
  }

  function scheduleUploads() {
    if (!isDesktopSettingsRuntime()) {
      startPendingUploads(DEFAULT_MAX_CONCURRENT_UPLOADS)
      return
    }

    if (scheduling) return
    scheduling = true
    void getDesktopSettings().then((settings) => {
      const maxConcurrentUploads = Math.max(1, Math.min(16, Math.floor(settings.maxConcurrentUploads || DEFAULT_MAX_CONCURRENT_UPLOADS)))
      startPendingUploads(maxConcurrentUploads)
    }).catch(() => {
      startPendingUploads(DEFAULT_MAX_CONCURRENT_UPLOADS)
    }).finally(() => {
      scheduling = false
      const activeCount = activeUploadCount()
      const hasPending = queue.value.some(item => !item.desktop && !item.started && item.status === 'pending')
      if (hasPending && activeCount === 0) scheduleUploads()
    })
  }

  function startPendingUploads(maxConcurrentUploads: number) {
    const activeCount = activeUploadCount()
    const slots = Math.max(0, maxConcurrentUploads - activeCount)
    if (slots === 0) return

    queue.value
      .filter(item => !item.desktop && !item.started && item.status === 'pending')
      .slice(0, slots)
      .forEach(item => {
        item.started = true
        void processUpload(item)
      })
  }

  function activeUploadCount() {
    return queue.value.filter(item =>
      !item.desktop
      && item.started
      && (item.status === 'hashing' || item.status === 'uploading')
    ).length
  }

  async function processUpload(item: UploadItem) {
    const isCanceled = () => queue.value.find(q => q.id === item.id)?.status === 'canceled'
    const update = (patch: Partial<UploadItem>) => {
      const i = queue.value.find(q => q.id === item.id)
      if (i?.status === 'canceled' && patch.status !== 'canceled') return
      if (i) Object.assign(i, normalizeUploadPatch(patch))
    }
    const label = item.displayName || item.fileName

    try {
      if (!item.file) {
        update({ status: 'resume_required', error: '请重新选择此文件以继续上传' })
        return
      }
      update({ status: 'hashing' })
      const md5 = await computeMd5(item.file, (pct) => update({ progress: pct * 30 }))
      if (isCanceled()) return
      if (!await waitWhilePaused(item.id)) return
      if (item.uploadId && item.md5Hash && item.md5Hash !== md5) {
        update({
          status: 'resume_required',
          error: '选择的文件与续传任务不匹配',
          progress: 0,
        })
        return
      }
      const totalChunks = item.file.size === 0 ? 0 : Math.ceil(item.file.size / CHUNK_SIZE)
      update({
        fileSize: item.file.size,
        md5Hash: md5,
        totalChunks,
      })

      const uploadIdentity = {
        parentId: item.parentId,
        fileName: item.fileName,
        displayName: item.displayName,
        fileSize: item.file.size,
        md5Hash: md5,
      }
      const resumeRecord = findResumableUpload(uploadIdentity)
      if (resumeRecord) {
        item.parentId = resumeRecord.parentId
        update({ parentId: resumeRecord.parentId })
        mergeRestoredQueueItem(item.id, resumeRecord)
      }
      if (isCanceled()) return

      const instant = await tryInstantUpload(item.parentId, item.fileName, item.file.size, md5, item.file.type || undefined)
      if (isCanceled()) return
      if (!await waitWhilePaused(item.id)) return
      if (instant) {
        removeResumableUpload(resumeRecord?.sessionId || item.uploadId)
        update({ status: 'instant', progress: 100, resumable: false })
        toast.success(`${label} 秒传成功`)
        useFilesStore().refresh()
        return
      }

      update({ status: 'uploading', progress: 30 })

      const session = await getOrCreateUploadSession(item, md5, totalChunks, resumeRecord)
      if (isCanceled()) {
        await uploadApi.abortMultipart(session.sessionId).catch(() => {})
        removeResumableUpload(session.sessionId)
        return
      }
      update({
        uploadId: session.sessionId,
        totalChunks: session.totalChunks,
        resumable: true,
      })
      if (session.totalChunks === 0) {
        await uploadApi.completeMultipart(session.sessionId)
        update({ status: 'done', progress: 100, chunkProgress: '', resumable: false })
        toast.success(`${label} 上传完成`)
        useFilesStore().refresh()
        return
      }
      saveResumableUpload({
        sessionId: session.sessionId,
        parentId: item.parentId,
        fileName: item.fileName,
        displayName: item.displayName,
        fileSize: item.file.size,
        md5Hash: md5,
        totalChunks: session.totalChunks,
        chunkSize: session.chunkSize,
        createdAt: resumeRecord?.sessionId === session.sessionId ? resumeRecord.createdAt : Date.now(),
        updatedAt: Date.now(),
      })

      const missingChunks = await getMissingChunks(session.sessionId, session.totalChunks).catch(() => range(session.totalChunks))
      if (isCanceled()) return
      updateProgressFromChunks(update, session.totalChunks, session.totalChunks - missingChunks.length)

      for (const i of missingChunks) {
        const current = queue.value.find(q => q.id === item.id)
        if (current?.status === 'canceled') return
        if (!await waitWhilePaused(item.id)) return
        const chunk = item.file.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)
        const missingIndex = missingChunks.indexOf(i)
        await uploadApi.uploadChunk(session.sessionId, i, chunk, (pct) => {
          const completedBeforeChunk = session.totalChunks - missingChunks.length
          const completedEquivalent = completedBeforeChunk + missingIndex + pct / 100
          const overall = 30 + (completedEquivalent / session.totalChunks) * 65
          update({ progress: overall, chunkProgress: `${Math.min(session.totalChunks, Math.floor(completedEquivalent))}/${session.totalChunks}` })
        })
        if (!await waitWhilePaused(item.id)) return
        updateProgressFromChunks(update, session.totalChunks, session.totalChunks - missingChunks.length + missingIndex + 1)
      }

      if (!await waitWhilePaused(item.id)) return
      await uploadApi.completeMultipart(session.sessionId)
      removeResumableUpload(session.sessionId)
      update({ status: 'done', progress: 100, chunkProgress: '', resumable: false })
      toast.success(`${label} 上传完成`)
      useFilesStore().refresh()
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '上传失败'
      update({ status: 'error', error: msg, resumable: !!item.uploadId })
      toast.error(`${label} 上传失败`)
    } finally {
      const current = queue.value.find(q => q.id === item.id)
      if (current) current.started = false
      scheduleUploads()
    }
  }

  async function cancelUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    if (item.desktop) {
      await cancelDesktopUpload(id).catch(() => {})
      Object.assign(item, { status: 'canceled' as UploadStatus, error: null, resumable: false })
      return
    }
    if (item.uploadId) {
      await uploadApi.abortMultipart(item.uploadId).catch(() => {})
      removeResumableUpload(item.uploadId)
    }
    Object.assign(item, { status: 'canceled' as UploadStatus, error: null, resumable: false, started: false })
    scheduleUploads()
  }

  function pauseUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    if (!['pending', 'hashing', 'uploading'].includes(item.status)) return
    item.pausedFrom = item.status as Exclude<UploadStatus, 'paused'>
    item.status = 'paused'
    item.error = null
    scheduleUploads()
  }

  function resumePausedUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item || item.status !== 'paused') return
    item.status = item.started ? (item.pausedFrom || 'uploading') : 'pending'
    item.pausedFrom = undefined
    scheduleUploads()
  }

  async function refreshUploadStatus(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item?.uploadId) return
    const { data } = await uploadApi.getUploadStatus(item.uploadId)
    const status = data.data
    if (status.uploadedChunks !== undefined && status.totalChunks) {
      item.chunkProgress = `${status.uploadedChunks}/${status.totalChunks}`
      item.progress = normalizeProgress(30 + (status.uploadedChunks / status.totalChunks) * 65)
    }
  }

  function resumeUploadWithFile(id: string, file: File) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    item.file = file
    item.error = null
    item.status = 'pending'
    item.started = false
    scheduleUploads()
  }

  function retryUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    if (!item.file) {
      Object.assign(item, { status: 'resume_required' as UploadStatus, error: '请重新选择此文件以继续上传' })
      return
    }
    Object.assign(item, { status: 'pending' as UploadStatus, error: null, started: false })
    scheduleUploads()
  }

  restoreResumableUploads()

  return {
    queue,
    isOpen,
    addFiles,
    addUploadFiles,
    applyDesktopUploadItem,
    removeItem,
    clearDone,
    cancelUpload,
    pauseUpload,
    resumePausedUpload,
    refreshUploadStatus,
    retryUpload,
    resumeUploadWithFile,
    restoreResumableUploads,
  }
})

function delay(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function waitWhilePaused(id: string) {
  const store = useUploadStore()
  while (true) {
    const item = store.queue.find(q => q.id === id)
    if (!item || item.status === 'canceled') return false
    if (item.status !== 'paused') return true
    await delay(100)
  }
}

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

async function getOrCreateUploadSession(item: UploadItem, md5Hash: string, totalChunks: number, resumeRecord: ResumableUploadRecord | null) {
  if (resumeRecord) {
    try {
      await uploadApi.getUploadStatus(resumeRecord.sessionId)
      return {
        sessionId: resumeRecord.sessionId,
        totalChunks: resumeRecord.totalChunks,
        chunkSize: resumeRecord.chunkSize || CHUNK_SIZE,
      }
    } catch {
      removeResumableUpload(resumeRecord.sessionId)
    }
  }

  const initRes = await uploadApi.initMultipart(item.parentId, item.fileName, item.fileSize ?? item.file!.size, md5Hash, totalChunks, item.file?.type || undefined)
  return {
    sessionId: initRes.data.data.sessionId,
    totalChunks: initRes.data.data.totalChunks || totalChunks,
    chunkSize: initRes.data.data.chunkSize || CHUNK_SIZE,
  }
}

async function getMissingChunks(sessionId: string, totalChunks: number) {
  const { data } = await uploadApi.getUploadStatus(sessionId)
  const missing = data.data.missingChunks
  if (Array.isArray(missing)) return missing
  const uploaded = data.data.uploadedChunks || 0
  return range(totalChunks).slice(uploaded)
}

function updateProgressFromChunks(update: (patch: Partial<UploadItem>) => void, totalChunks: number, uploadedChunks: number) {
  update({
    chunkProgress: `${uploadedChunks}/${totalChunks}`,
    progress: normalizeProgress(30 + (uploadedChunks / totalChunks) * 65),
  })
}

function range(length: number) {
  return Array.from({ length }, (_, index) => index)
}

function mergeRestoredQueueItem(activeId: string, record: ResumableUploadRecord) {
  const store = useUploadStore()
  const duplicateIndex = store.queue.findIndex(item => item.id !== activeId && item.uploadId === record.sessionId)
  if (duplicateIndex >= 0) {
    store.queue.splice(duplicateIndex, 1)
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
