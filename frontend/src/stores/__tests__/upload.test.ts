import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { toast } from 'vue-sonner'
import { useUploadStore } from '@/stores/upload'
import { useFilesStore } from '@/stores/files'
import * as uploadApi from '@/api/upload'
import * as resumableUpload from '@/api/resumableUpload'
import * as desktopSettings from '@/api/desktopSettings'

vi.mock('@/api/upload', () => ({
  instantUpload: vi.fn(),
  initMultipart: vi.fn(),
  uploadChunk: vi.fn(),
  completeMultipart: vi.fn(),
  abortMultipart: vi.fn(),
  getUploadStatus: vi.fn(),
}))

vi.mock('@/api/resumableUpload', () => ({
  findResumableUpload: vi.fn(),
  removeResumableUpload: vi.fn(),
  readResumableUploads: vi.fn(() => []),
  saveResumableUpload: vi.fn(),
}))

vi.mock('@/api/desktopSettings', () => ({
  getDesktopSettings: vi.fn(),
  isDesktopSettingsRuntime: vi.fn(() => false),
}))

class MockFileReader {
  result: ArrayBuffer | null = null
  error: Error | null = null
  onload: ((event: ProgressEvent<FileReader>) => void) | null = null
  onerror: (() => void) | null = null

  readAsArrayBuffer() {
    this.result = new ArrayBuffer(8)
    setTimeout(() => {
      this.onload?.({ target: this } as unknown as ProgressEvent<FileReader>)
    }, 0)
  }
}

const instantUpload = uploadApi.instantUpload as Mock
const initMultipart = uploadApi.initMultipart as Mock
const uploadChunk = uploadApi.uploadChunk as Mock
const completeMultipart = uploadApi.completeMultipart as Mock
const abortMultipart = uploadApi.abortMultipart as Mock
const getUploadStatus = uploadApi.getUploadStatus as Mock
const findResumableUpload = resumableUpload.findResumableUpload as Mock
const removeResumableUpload = resumableUpload.removeResumableUpload as Mock
const readResumableUploads = resumableUpload.readResumableUploads as Mock
const saveResumableUpload = resumableUpload.saveResumableUpload as Mock
const getDesktopSettings = desktopSettings.getDesktopSettings as Mock
const isDesktopSettingsRuntime = desktopSettings.isDesktopSettingsRuntime as Mock
const toastError = toast.error as Mock
const toastSuccess = toast.success as Mock

const flushAsync = async () => {
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

const mockFilesRefresh = () => {
  return vi.spyOn(useFilesStore(), 'refresh').mockResolvedValue(undefined)
}

const makeFile = (size = 3, name = 'demo.txt') => {
  return new File(['x'.repeat(size)], name, { type: 'text/plain' })
}

const deferred = <T = unknown>() => {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.stubGlobal('FileReader', MockFileReader)
  instantUpload.mockReset()
  initMultipart.mockReset()
  uploadChunk.mockReset()
  completeMultipart.mockReset()
  abortMultipart.mockReset()
  getUploadStatus.mockReset()
  getUploadStatus.mockResolvedValue({ data: { data: { totalChunks: 1, uploadedChunks: 0 } } })
  findResumableUpload.mockReset()
  findResumableUpload.mockReturnValue(null)
  removeResumableUpload.mockReset()
  readResumableUploads.mockReset()
  readResumableUploads.mockReturnValue([])
  saveResumableUpload.mockReset()
  getDesktopSettings.mockReset()
  getDesktopSettings.mockResolvedValue({ maxConcurrentUploads: 3 })
  isDesktopSettingsRuntime.mockReset()
  isDesktopSettingsRuntime.mockReturnValue(false)
  toastError.mockReset()
  toastSuccess.mockReset()
})

describe('upload store', () => {
  it('limits browser file uploads to three active tasks', async () => {
    const gates = Array.from({ length: 5 }, () => deferred<{ data: { data: { fileId: number; instant: true } } }>())
    let instantIndex = 0
    instantUpload.mockImplementation(() => gates[instantIndex++].promise)
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([
      makeFile(3, 'a.txt'),
      makeFile(3, 'b.txt'),
      makeFile(3, 'c.txt'),
      makeFile(3, 'd.txt'),
      makeFile(3, 'e.txt'),
    ], 10)
    await flushAsync()

    expect(store.queue.filter(item => item.status === 'hashing')).toHaveLength(3)
    expect(store.queue.filter(item => item.status === 'pending')).toHaveLength(2)
    expect(instantUpload).toHaveBeenCalledTimes(3)

    gates[0].resolve({ data: { data: { fileId: 1, instant: true } } })
    await flushAsync()
    await vi.waitFor(() => expect(instantUpload).toHaveBeenCalledTimes(4))

    expect(store.queue.filter(item => item.status === 'hashing')).toHaveLength(3)
    expect(store.queue[3]).toMatchObject({ fileName: 'd.txt', status: 'hashing' })

    gates[1].resolve({ data: { data: { fileId: 2, instant: true } } })
    gates[2].resolve({ data: { data: { fileId: 3, instant: true } } })
    gates[3].resolve({ data: { data: { fileId: 4, instant: true } } })
    await vi.waitFor(() => expect(instantUpload).toHaveBeenCalledTimes(5))
    gates[4].resolve({ data: { data: { fileId: 5, instant: true } } })
    await flushAsync()
  })

  it('uses desktop upload concurrency settings when running in Tauri', async () => {
    isDesktopSettingsRuntime.mockReturnValue(true)
    getDesktopSettings.mockResolvedValue({ maxConcurrentUploads: 2 })
    const gates = Array.from({ length: 4 }, () => deferred<{ data: { data: { fileId: number; instant: true } } }>())
    let instantIndex = 0
    instantUpload.mockImplementation(() => gates[instantIndex++].promise)
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([
      makeFile(3, 'a.txt'),
      makeFile(3, 'b.txt'),
      makeFile(3, 'c.txt'),
      makeFile(3, 'd.txt'),
    ], 10)
    await vi.waitFor(() => expect(instantUpload).toHaveBeenCalledTimes(2))
    await flushAsync()

    expect(store.queue.filter(item => item.status === 'hashing')).toHaveLength(2)
    expect(store.queue.filter(item => item.status === 'pending')).toHaveLength(2)

    gates[0].resolve({ data: { data: { fileId: 1, instant: true } } })
    gates[1].resolve({ data: { data: { fileId: 2, instant: true } } })
    await vi.waitFor(() => expect(instantUpload).toHaveBeenCalledTimes(4))
    gates[2].resolve({ data: { data: { fileId: 3, instant: true } } })
    gates[3].resolve({ data: { data: { fileId: 4, instant: true } } })
    await flushAsync()
  })

  it('starts the next pending upload after canceling an active upload', async () => {
    const gates = Array.from({ length: 4 }, () => deferred<{ data: { data: { fileId: number; instant: true } } }>())
    let instantIndex = 0
    instantUpload.mockImplementation(() => gates[instantIndex++].promise)
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([
      makeFile(3, 'a.txt'),
      makeFile(3, 'b.txt'),
      makeFile(3, 'c.txt'),
      makeFile(3, 'd.txt'),
    ], 10)
    await flushAsync()

    await store.cancelUpload(store.queue[0].id)
    await flushAsync()
    await vi.waitFor(() => expect(instantUpload).toHaveBeenCalledTimes(4))

    expect(store.queue[0]).toMatchObject({ status: 'canceled' })
    expect(store.queue[3]).toMatchObject({ fileName: 'd.txt', status: 'hashing' })
  })

  it('pauses and resumes an active upload before chunk transfer', async () => {
    const gate = deferred<{ data: { data: { chunkNumber: number } } }>()
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'pause-session', totalChunks: 1 } } })
    uploadChunk.mockReturnValue(gate.promise)
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 2 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(store.queue[0].status).toBe('uploading'))

    store.pauseUpload(store.queue[0].id)
    await flushAsync()
    expect(store.queue[0].status).toBe('paused')

    store.resumePausedUpload(store.queue[0].id)
    await vi.waitFor(() => expect(uploadChunk).toHaveBeenCalled())
    gate.resolve({ data: { data: { chunkNumber: 0 } } })
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
  })

  it('marks an item as instant when instant upload succeeds', async () => {
    const refresh = mockFilesRefresh()
    instantUpload.mockResolvedValue({ data: { data: { fileId: 1, instant: true } } })

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await flushAsync()

    expect(store.queue[0]).toMatchObject({ status: 'instant', progress: 100 })
    expect(initMultipart).not.toHaveBeenCalled()
    expect(refresh).toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalledWith('demo.txt 秒传成功')
  })

  it('falls back to chunked upload when instant upload returns 419010', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'session-1', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 2 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initMultipart).toHaveBeenCalledWith(10, 'demo.txt', 3, expect.any(String), 1, 'text/plain')
    expect(uploadChunk).toHaveBeenCalledWith('session-1', 0, expect.any(Blob), expect.any(Function))
    expect(completeMultipart).toHaveBeenCalledWith('session-1')
    expect(saveResumableUpload).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 'session-1',
      parentId: 10,
      fileName: 'demo.txt',
      displayName: 'demo.txt',
    }))
    expect(removeResumableUpload).toHaveBeenCalledWith('session-1')
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100, chunkProgress: '' })
    expect(toastError).not.toHaveBeenCalledWith(expect.stringContaining('no matching'))
  })

  it('completes zero-byte uploads without sending chunks', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'empty-session', totalChunks: 0, chunkSize: 5 * 1024 * 1024 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 9 } } })
    const refresh = mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile(0, 'empty.txt')], null)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(instantUpload).toHaveBeenCalledWith(null, 'empty.txt', 0, expect.any(String), 'text/plain')
    expect(initMultipart).toHaveBeenCalledWith(null, 'empty.txt', 0, expect.any(String), 0, 'text/plain')
    expect(uploadChunk).not.toHaveBeenCalled()
    expect(saveResumableUpload).not.toHaveBeenCalled()
    expect(removeResumableUpload).not.toHaveBeenCalledWith('empty-session')
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100, chunkProgress: '' })
    expect(refresh).toHaveBeenCalled()
  })

  it('uploads every chunk and records progress for larger files', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'session-large', totalChunks: 2 } } })
    uploadChunk.mockImplementation(async (_sessionId, _idx, _blob, onProgress) => {
      onProgress(100)
      return { data: { data: { chunkNumber: _idx } } }
    })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 3 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile(6 * 1024 * 1024, 'large.bin')], 10)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initMultipart).toHaveBeenCalledWith(10, 'large.bin', 6 * 1024 * 1024, expect.any(String), 2, 'text/plain')
    expect(uploadChunk).toHaveBeenCalledTimes(2)
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100 })
  })

  it('allows root uploads with null parent id', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'root-session', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 4 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile()], null)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(instantUpload).toHaveBeenCalledWith(null, 'demo.txt', 3, expect.any(String), 'text/plain')
    expect(initMultipart).toHaveBeenCalledWith(null, 'demo.txt', 3, expect.any(String), 1, 'text/plain')
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100 })
  })

  it('uploads entries to their own target folders and keeps display names', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'nested-session', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 5 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addUploadFiles([{
      file: makeFile(3, 'demo.txt'),
      parentId: 42,
      displayName: 'docs/nested/demo.txt',
    }])
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(instantUpload).toHaveBeenCalledWith(42, 'demo.txt', 3, expect.any(String), 'text/plain')
    expect(initMultipart).toHaveBeenCalledWith(42, 'demo.txt', 3, expect.any(String), 1, 'text/plain')
    expect(store.queue[0]).toMatchObject({
      displayName: 'docs/nested/demo.txt',
      parentId: 42,
      status: 'done',
    })
  })

  it('resumes an existing upload session and uploads only missing chunks', async () => {
    findResumableUpload.mockReturnValue({
      sessionId: 'resume-session',
      parentId: 10,
      fileName: 'large.bin',
      displayName: 'large.bin',
      fileSize: 11 * 1024 * 1024,
      md5Hash: expect.any(String),
      totalChunks: 3,
      chunkSize: 5 * 1024 * 1024,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    getUploadStatus.mockResolvedValue({ data: { data: { totalChunks: 3, uploadedChunks: 1, missingChunks: [1, 2] } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 1 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 6 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile(11 * 1024 * 1024, 'large.bin')], 10)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initMultipart).not.toHaveBeenCalled()
    expect(uploadChunk).toHaveBeenCalledTimes(2)
    expect(uploadChunk.mock.calls.map(call => call[1])).toEqual([1, 2])
    expect(completeMultipart).toHaveBeenCalledWith('resume-session')
    expect(removeResumableUpload).toHaveBeenCalledWith('resume-session')
    expect(store.queue[0]).toMatchObject({ uploadId: 'resume-session', status: 'done' })
  })

  it('restores resumable records as tasks that require selecting the file again', async () => {
    readResumableUploads.mockReturnValue([{
      sessionId: 'stored-session',
      parentId: null,
      fileName: 'pending.txt',
      displayName: 'pending.txt',
      fileSize: 100,
      md5Hash: 'stored-md5',
      totalChunks: 2,
      chunkSize: 5 * 1024 * 1024,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }])
    getUploadStatus.mockResolvedValue({ data: { data: { totalChunks: 2, uploadedChunks: 1, missingChunks: [1] } } })

    const store = useUploadStore()
    await flushAsync()

    expect(store.queue).toHaveLength(1)
    expect(store.queue[0]).toMatchObject({
      status: 'resume_required',
      uploadId: 'stored-session',
      chunkProgress: '1/2',
      resumable: true,
    })
  })

  it('keeps a failed upload resumable instead of clearing its session', async () => {
    instantUpload.mockRejectedValue({ response: { data: { code: 419010 } } })
    initMultipart.mockResolvedValue({ data: { data: { sessionId: 'failed-session', totalChunks: 1 } } })
    uploadChunk.mockRejectedValue(new Error('network down'))

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith('demo.txt 上传失败'))

    expect(saveResumableUpload).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'failed-session' }))
    expect(removeResumableUpload).not.toHaveBeenCalledWith('failed-session')
    expect(store.queue[0]).toMatchObject({ status: 'error', uploadId: 'failed-session', resumable: true })
  })

  it('rejects a different file when resuming a restored task', async () => {
    const store = useUploadStore()
    store.queue.push({
      id: 'restored-1',
      fileName: 'original.txt',
      displayName: 'original.txt',
      parentId: null,
      status: 'resume_required',
      progress: 0,
      chunkProgress: '',
      error: null,
      uploadId: 'resume-session',
      fileSize: 8,
      md5Hash: 'other-md5',
      totalChunks: 1,
      resumable: true,
    })

    store.resumeUploadWithFile('restored-1', makeFile(8, 'original.txt'))
    await flushAsync()

    expect(uploadChunk).not.toHaveBeenCalled()
    expect(store.queue[0]).toMatchObject({
      status: 'resume_required',
      error: '选择的文件与续传任务不匹配',
    })
  })

  it('rejects a selected file that does not match the restored md5', async () => {
    const store = useUploadStore()
    store.queue.push({
      id: 'resume-1',
      fileName: 'bad.bin',
      displayName: 'bad.bin',
      parentId: null,
      status: 'resume_required',
      progress: 0,
      chunkProgress: '',
      error: null,
      uploadId: 'bad-session',
      fileSize: 3,
      md5Hash: 'different-md5',
      totalChunks: 1,
      resumable: true,
    })

    store.resumeUploadWithFile('resume-1', makeFile(3, 'bad.bin'))
    await flushAsync()

    expect(store.queue[0]).toMatchObject({
      status: 'resume_required',
      error: '选择的文件与续传任务不匹配',
    })
    expect(uploadChunk).not.toHaveBeenCalled()
  })
})
