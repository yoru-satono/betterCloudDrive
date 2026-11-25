import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t
import { defineStore } from 'pinia'
import { ref, onScopeDispose } from 'vue'
import { resolveMessage } from '@/i18n'
import SparkMD5 from 'spark-md5'
import * as uploadApi from '@/api/upload'
import { cancelDesktopUpload } from '@/api/desktopUpload'
import {
  findResumableUpload,
  removeResumableUpload,
  readResumableUploads,
  saveResumableUpload,
  clearLegacyResumableRecords,
  createResumableScope,
  type ResumableScope,
  type ResumableUploadRecord,
} from '@/api/resumableUpload'
import { useFilesStore } from './files'
import { useTeamFilesStore } from './teamFiles'
import { toast } from 'vue-sonner'
import { getDesktopSettings, isDesktopSettingsRuntime } from '@/api/desktopSettings'
import { identityRevision } from '@/platform/identity'

export interface UploadIdentity {
  readonly scope: ResumableScope
  readonly revision: number
}

export type UploadStatus = 'pending' | 'hashing' | 'uploading' | 'paused' | 'done' | 'error' | 'instant' | 'canceled' | 'resume_required'

const CHUNK_SIZE = 5 * 1024 * 1024
const DEFAULT_MAX_CONCURRENT_UPLOADS = 3

export interface UploadItem {
  id: string
  teamId?: number | null
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
  teamId?: number | null
  parentId: number | null
  fileName?: string
  displayName?: string
}

interface DesktopUploadPatch {
  id: string
  teamId?: number | null
  fileName: string
  displayName: string
  parentId: number | null
  status: UploadStatus
  progress: number
  chunkProgress: string
  error?: string | null
  uploadId?: string | null
  fileSize: number
  md5Hash?: string | null
  totalChunks: number
  resumable: boolean
}

export const useUploadStore = defineStore('upload', () => {
  const queue = ref<UploadItem[]>([])
  const isOpen = ref(false)
  let identity: UploadIdentity | undefined
  let scheduling: UploadIdentity | undefined
  clearLegacyResumableRecords()

  function isIdentityCurrent(expected: UploadIdentity | undefined): expected is UploadIdentity {
    return !!expected && identity === expected && expected.revision === identityRevision() && !!localStorage.getItem('accessToken')
  }

  function assertUploadIdentity(expected: UploadIdentity) {
    if (!isIdentityCurrent(expected)) throw new Error('SESSION_CHANGED')
  }

  function captureIdentity(): UploadIdentity {
    if (!isIdentityCurrent(identity)) throw new Error('AUTH_REQUIRED')
    return identity
  }

  const currentScope = () => isIdentityCurrent(identity) ? identity.scope : undefined

  function resetIdentity() {
    identity = scheduling = undefined
    for (const item of queue.value) item.status = 'canceled'
    queue.value = []
    isOpen.value = false
  }
  window.addEventListener('bcd:identity-invalidated', resetIdentity)
  onScopeDispose(() => { resetIdentity(); window.removeEventListener('bcd:identity-invalidated', resetIdentity) })

  // Called only after profile validation and native session acknowledgement.
  function activateIdentity(apiBaseUrl: string, userId: number) {
    const scope = createResumableScope(apiBaseUrl, userId)
    if (isIdentityCurrent(identity) && identity.scope.apiBaseUrl === scope.apiBaseUrl && identity.scope.userId === scope.userId) return
    if (!localStorage.getItem('accessToken')) throw new Error('AUTH_REQUIRED')
    if (identity) resetIdentity()
    identity = Object.freeze({ scope, revision: identityRevision() })
    restoreResumableUploads()
  }

  function addFiles(files: File[], parentId: number | null, teamId?: number | null) {
    addUploadFiles(files.map(file => ({ file, parentId, teamId: teamId ?? null })))
  }

  function addUploadFiles(entries: UploadFileEntry[]) {
    if (!entries.length) return
    captureIdentity()
    const items: UploadItem[] = entries.map(entry => ({
      id: crypto.randomUUID(),
      teamId: entry.teamId ?? null,
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
    if (!isIdentityCurrent(identity)) return
    const expected = identity
    const records = readResumableUploads(expected.scope).filter(record => !queue.value.some(item => !item.desktop && item.uploadId === record.sessionId))
    if (!records.length) return
    const restored: UploadItem[] = records.map(record => ({
      id: crypto.randomUUID(),
      fileName: record.fileName,
      teamId: record.teamId ?? null,
      displayName: record.displayName,
      parentId: record.parentId,
      status: 'resume_required',
      progress: 0,
      chunkProgress: '',
      error: __t('transferUi.upload.pleaseReselectThisFileToContinueUploading'),
      uploadId: record.sessionId,
      fileSize: record.fileSize,
      md5Hash: record.md5Hash,
      totalChunks: record.totalChunks,
      resumable: true,
    }))
    queue.value.push(...restored)
    isOpen.value = true
    restored.forEach(item => refreshUploadStatus(item.id).catch(error => {
      if (!isIdentityCurrent(expected)) return
      const code = (error as { response?: { data?: { code?: number } } }).response?.data?.code
      const expired = code === 419006 || code === 404001
      if (expired && item.uploadId) removeResumableUpload(expected.scope, item.uploadId)
      const current = queue.value.find(entry => entry.id === item.id)
      if (!current) return
      Object.assign(current, {
        status: 'error' as UploadStatus,
        error: expired ? __t('transferUi.upload.theResumeSessionHasExpiredPleaseUploadAgain') : uploadError(error),
        resumable: !expired,
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

  function applyDesktopUploadItem(item: DesktopUploadPatch) {
    const existing = queue.value.find(entry => entry.id === item.id)
    const next = normalizeUploadPatch({
      ...item,
      error: item.error ?? null,
      uploadId: item.uploadId ?? undefined,
      md5Hash: item.md5Hash ?? undefined,
      desktop: true,
    })
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
    if (!isIdentityCurrent(identity)) return
    const expected = identity
    if (!isDesktopSettingsRuntime()) {
      startPendingUploads(DEFAULT_MAX_CONCURRENT_UPLOADS, expected)
      return
    }

    if (scheduling) return
    scheduling = expected
    void getDesktopSettings().then((settings) => {
      const maxConcurrentUploads = Math.max(1, Math.min(16, Math.floor(settings.maxConcurrentUploads || DEFAULT_MAX_CONCURRENT_UPLOADS)))
      startPendingUploads(maxConcurrentUploads, expected)
    }).catch(() => {
      startPendingUploads(DEFAULT_MAX_CONCURRENT_UPLOADS, expected)
    }).finally(() => {
      if (scheduling !== expected || !isIdentityCurrent(expected)) return
      scheduling = undefined
      const activeCount = activeUploadCount()
      const hasPending = queue.value.some(item => !item.desktop && !item.started && item.status === 'pending')
      if (hasPending && activeCount === 0) scheduleUploads()
    })
  }

  function startPendingUploads(maxConcurrentUploads: number, expected: UploadIdentity) {
    if (!isIdentityCurrent(expected)) return
    const activeCount = activeUploadCount()
    const slots = Math.max(0, maxConcurrentUploads - activeCount)
    if (slots === 0) return

    queue.value
      .filter(item => !item.desktop && !item.started && item.status === 'pending')
      .slice(0, slots)
      .forEach(item => {
        item.started = true
        void processUpload(item, expected)
      })
  }

  function activeUploadCount() {
    return queue.value.filter(item =>
      !item.desktop
      && item.started
      && (item.status === 'hashing' || item.status === 'uploading')
    ).length
  }

  async function processUpload(item: UploadItem, expected: UploadIdentity) {
    const check = () => assertUploadIdentity(expected)
    const isCanceled = () => !isIdentityCurrent(expected) || !queue.value.some(q => q.id === item.id && q.status !== 'canceled')
    const wait = () => waitWhilePaused(item.id, expected)
    const update = (patch: Partial<UploadItem>) => {
      if (!isIdentityCurrent(expected)) return
      const i = queue.value.find(q => q.id === item.id)
      if (i?.status === 'canceled' && patch.status !== 'canceled') return
      if (i) Object.assign(i, normalizeUploadPatch(patch))
    }
    const label = item.displayName || item.fileName

    try {
      if (!item.file) {
        update({ status: 'resume_required', error: __t('transferUi.upload.pleaseReselectThisFileToContinueUploading') })
        return
      }
      update({ status: 'hashing' })
      const md5 = await computeMd5(item.file, (pct) => update({ progress: pct * 30 }))
      if (isCanceled()) return
      if (!await wait() || isCanceled()) return
      if (item.uploadId && item.md5Hash && item.md5Hash !== md5) {
        update({
          status: 'resume_required',
          error: __t('transferUi.upload.theSelectedFileDoesNotMatchTheResumeUploadTask'),
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
        teamId: item.teamId ?? null,
        fileName: item.fileName,
        displayName: item.displayName,
        fileSize: item.file.size,
        md5Hash: md5,
      }
      const resumeRecord = findResumableUpload(expected.scope, uploadIdentity)
      if (resumeRecord) {
        item.parentId = resumeRecord.parentId
        update({ parentId: resumeRecord.parentId })
        mergeRestoredQueueItem(item.id, resumeRecord)
      }
      if (isCanceled()) return

      const uploadContext = item.teamId ? { teamId: item.teamId } : undefined
      const result = await getOrCreateUploadSession(item, md5, resumeRecord, expected.scope, check)
      if (!isIdentityCurrent(expected)) return
      if (result.mode === 'INSTANT') {
        removeResumableUpload(expected.scope, resumeRecord?.sessionId || item.uploadId)
        if (isCanceled()) return
        if (!await wait() || isCanceled()) return
        update({ status: 'instant', progress: 100, resumable: false, uploadId: undefined })
        toast.success(__t('transferUi.upload.transmissionSuccessfulInSeconds', { p0: label }))
        refreshFileSpace(item.teamId)
        return
      }
      const session = result
      if (isCanceled() || !await wait()) {
        if (!isIdentityCurrent(expected)) return
        await abortMultipart(session.sessionId, uploadContext).catch(() => {})
        if (!isIdentityCurrent(expected)) return
        removeResumableUpload(expected.scope, session.sessionId)
        return
      }
      if (isCanceled()) return
      update({
        status: 'uploading', progress: 30,
        uploadId: session.sessionId,
        totalChunks: session.totalChunks,
        resumable: true,
      })
      saveResumableUpload(expected.scope, {
        sessionId: session.sessionId,
        teamId: item.teamId ?? null,
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

      if (session.totalChunks === 0) {
        await completeMultipart(session.sessionId, uploadContext)
        check()
        removeResumableUpload(expected.scope, session.sessionId)
        update({ status: 'done', progress: 100, chunkProgress: '', resumable: false })
        toast.success(__t('transferUi.upload.uploadCompleted', { p0: label }))
        refreshFileSpace(item.teamId)
        return
      }

      const missingChunks = await getMissingChunks(session.sessionId, session.totalChunks, uploadContext)
      if (isCanceled()) return
      updateProgressFromChunks(update, session.totalChunks, session.totalChunks - missingChunks.length)

      for (const i of missingChunks) {
        const current = queue.value.find(q => q.id === item.id)
        if (current?.status === 'canceled') return
        if (!await wait() || isCanceled()) return
        const chunk = item.file.slice(i * session.chunkSize, (i + 1) * session.chunkSize)
        const missingIndex = missingChunks.indexOf(i)
        await uploadChunk(session.sessionId, i, chunk, (pct) => {
          const completedBeforeChunk = session.totalChunks - missingChunks.length
          const completedEquivalent = completedBeforeChunk + missingIndex + pct / 100
          const overall = 30 + (completedEquivalent / session.totalChunks) * 65
          update({ progress: overall, chunkProgress: `${Math.min(session.totalChunks, Math.floor(completedEquivalent))}/${session.totalChunks}` })
        }, uploadContext)
        if (!await wait() || isCanceled()) return
        updateProgressFromChunks(update, session.totalChunks, session.totalChunks - missingChunks.length + missingIndex + 1)
      }

      if (!await wait() || isCanceled()) return
      await completeMultipart(session.sessionId, uploadContext)
      check()
      removeResumableUpload(expected.scope, session.sessionId)
      update({ status: 'done', progress: 100, chunkProgress: '', resumable: false })
      toast.success(__t('transferUi.upload.uploadCompleted', { p0: label }))
      refreshFileSpace(item.teamId)
    } catch (e: unknown) {
      if (!isIdentityCurrent(expected) || isCanceled()) return
      const code = (e as { response?: { data?: { code?: number } } }).response?.data?.code
      if ((code === 419006 || code === 419016 || code === 419017) && item.uploadId) {
        await abortMultipart(item.uploadId, item.teamId ? { teamId: item.teamId } : undefined).catch(() => {})
        if (!isIdentityCurrent(expected)) return
        removeResumableUpload(expected.scope, item.uploadId)
        update({ uploadId: undefined, md5Hash: undefined, resumable: false })
      }
      update({ status: 'error', error: uploadError(e), resumable: !!item.uploadId })
      toast.error(__t('transferUi.upload.namedUploadFailed', { p0: label }))
    } finally {
      if (isIdentityCurrent(expected)) {
        const current = queue.value.find(q => q.id === item.id)
        if (current) current.started = false
        scheduleUploads()
      }
    }
  }

  async function cancelUpload(id: string) {
    const item = queue.value.find(q => q.id === id)
    if (!item) return
    const expected = captureIdentity()
    if (item.desktop) {
      await cancelDesktopUpload(id).catch(() => {})
      if (!isIdentityCurrent(expected)) return
      Object.assign(item, { status: 'canceled' as UploadStatus, error: null, resumable: false })
      return
    }
    item.status = 'canceled'
    if (item.uploadId) {
      await abortMultipart(item.uploadId, item.teamId ? { teamId: item.teamId } : undefined).catch(() => {})
      if (!isIdentityCurrent(expected)) return
      removeResumableUpload(expected.scope, item.uploadId)
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
    const expected = captureIdentity()
    const { data } = await getUploadStatus(item.uploadId, item.teamId ? { teamId: item.teamId } : undefined)
    assertUploadIdentity(expected)
    if (!queue.value.some(entry => entry.id === id)) return
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
      Object.assign(item, { status: 'resume_required' as UploadStatus, error: __t('transferUi.upload.pleaseReselectThisFileToContinueUploading') })
      return
    }
    Object.assign(item, { status: 'pending' as UploadStatus, error: null, started: false })
    scheduleUploads()
  }

  async function waitWhilePaused(id: string, expected: UploadIdentity) {
    while (isIdentityCurrent(expected)) {
      const item = queue.value.find(entry => entry.id === id)
      if (!item || item.status === 'canceled') return false
      if (item.status !== 'paused') return true
      await delay(100)
    }
    return false
  }

  return {
    queue,
    isOpen,
    activateIdentity,
    captureIdentity,
    assertUploadIdentity,
    currentScope,
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

function uploadError(error: unknown) {
  const envelope = (error as { response?: { data?: { messageKey?: string } } })?.response?.data
  return envelope?.messageKey ? resolveMessage(envelope.messageKey) : error instanceof Error ? error.message : __t('transferUi.upload.uploadFailed')
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

async function getOrCreateUploadSession(item: UploadItem, md5Hash: string, resumeRecord: ResumableUploadRecord | null, scope: ResumableScope, check: () => void): Promise<uploadApi.UploadInitResult> {
  check()
  if (resumeRecord) {
    try {
      await getUploadStatus(resumeRecord.sessionId, item.teamId ? { teamId: item.teamId } : undefined)
      check()
      return {
        mode: 'MULTIPART',
        sessionId: resumeRecord.sessionId,
        totalChunks: resumeRecord.totalChunks,
        chunkSize: resumeRecord.chunkSize || CHUNK_SIZE,
      }
    } catch (error) {
      check()
      const code = (error as { response?: { data?: { code?: number } } }).response?.data?.code
      if (code !== 419006 && code !== 404001) throw error
      removeResumableUpload(scope, resumeRecord.sessionId)
    }
  }

  const context = item.teamId ? { teamId: item.teamId } : undefined
  check()
  const initArgs = [item.parentId, item.fileName, item.fileSize ?? item.file!.size, md5Hash] as const
  const response = context ? await uploadApi.initUpload(...initArgs, context) : await uploadApi.initUpload(...initArgs)
  return response.data.data
}

async function getMissingChunks(sessionId: string, totalChunks: number, context?: uploadApi.UploadContext) {
  const { data } = await getUploadStatus(sessionId, context)
  const missing = data.data.missingChunks
  if (Array.isArray(missing)) return missing
  const uploaded = data.data.uploadedChunks || 0
  return range(totalChunks).slice(uploaded)
}

function uploadChunk(
  sessionId: string,
  chunkNumber: number,
  chunk: Blob,
  onProgress: (pct: number) => void,
  context?: uploadApi.UploadContext,
) {
  return context
    ? uploadApi.uploadChunk(sessionId, chunkNumber, chunk, onProgress, context)
    : uploadApi.uploadChunk(sessionId, chunkNumber, chunk, onProgress)
}

function completeMultipart(sessionId: string, context?: uploadApi.UploadContext) {
  return context
    ? uploadApi.completeMultipart(sessionId, context)
    : uploadApi.completeMultipart(sessionId)
}

function abortMultipart(sessionId: string, context?: uploadApi.UploadContext) {
  return context
    ? uploadApi.abortMultipart(sessionId, context)
    : uploadApi.abortMultipart(sessionId)
}

function getUploadStatus(sessionId: string, context?: uploadApi.UploadContext) {
  return context
    ? uploadApi.getUploadStatus(sessionId, context)
    : uploadApi.getUploadStatus(sessionId)
}

function refreshFileSpace(teamId?: number | null) {
  if (teamId) {
    useTeamFilesStore().refreshIfCurrent(teamId)
    return
  }
  useFilesStore().refresh()
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
