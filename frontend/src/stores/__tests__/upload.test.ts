import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { invalidateIdentity } from '@/platform/identity'
import { toast } from 'vue-sonner'
import { useUploadStore } from '@/stores/upload'
import { useFilesStore } from '@/stores/files'
import * as uploadApi from '@/api/upload'
import * as resumableUpload from '@/api/resumableUpload'
import * as desktopSettings from '@/api/desktopSettings'

vi.mock('@/api/upload', () => ({
  initUpload: vi.fn(),
  uploadChunk: vi.fn(),
  completeMultipart: vi.fn(),
  abortMultipart: vi.fn(),
  getUploadStatus: vi.fn(),
}))

vi.mock('@/api/resumableUpload', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/resumableUpload')>(),
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

const initUpload = uploadApi.initUpload as Mock
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
  invalidateIdentity('test')
  setActivePinia(createPinia())
  localStorage.clear()
  vi.stubGlobal('FileReader', MockFileReader)
  initUpload.mockReset()
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
  localStorage.setItem('accessToken', 'synthetic-access')
  useUploadStore().activateIdentity('/api/v1', 7)
})

describe('upload store', () => {
  it('limits browser file uploads to three active tasks', async () => {
    const gates = Array.from({ length: 5 }, () => deferred<{ data: { data: { fileId: number; mode: 'INSTANT' } } }>())
    let instantIndex = 0
    initUpload.mockImplementation(() => gates[instantIndex++].promise)
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
    expect(initUpload).toHaveBeenCalledTimes(3)

    gates[0].resolve({ data: { data: { fileId: 1, mode: 'INSTANT' } } })
    await flushAsync()
    await vi.waitFor(() => expect(initUpload).toHaveBeenCalledTimes(4))

    expect(store.queue.filter(item => item.status === 'hashing')).toHaveLength(3)
    expect(store.queue[3]).toMatchObject({ fileName: 'd.txt', status: 'hashing' })

    gates[1].resolve({ data: { data: { fileId: 2, mode: 'INSTANT' } } })
    gates[2].resolve({ data: { data: { fileId: 3, mode: 'INSTANT' } } })
    gates[3].resolve({ data: { data: { fileId: 4, mode: 'INSTANT' } } })
    await vi.waitFor(() => expect(initUpload).toHaveBeenCalledTimes(5))
    gates[4].resolve({ data: { data: { fileId: 5, mode: 'INSTANT' } } })
    await flushAsync()
  })

  it('uses desktop upload concurrency settings when running in desktop', async () => {
    isDesktopSettingsRuntime.mockReturnValue(true)
    getDesktopSettings.mockResolvedValue({ maxConcurrentUploads: 2 })
    const gates = Array.from({ length: 4 }, () => deferred<{ data: { data: { fileId: number; mode: 'INSTANT' } } }>())
    let instantIndex = 0
    initUpload.mockImplementation(() => gates[instantIndex++].promise)
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([
      makeFile(3, 'a.txt'),
      makeFile(3, 'b.txt'),
      makeFile(3, 'c.txt'),
      makeFile(3, 'd.txt'),
    ], 10)
    await vi.waitFor(() => expect(initUpload).toHaveBeenCalledTimes(2))
    await flushAsync()

    expect(store.queue.filter(item => item.status === 'hashing')).toHaveLength(2)
    expect(store.queue.filter(item => item.status === 'pending')).toHaveLength(2)

    gates[0].resolve({ data: { data: { fileId: 1, mode: 'INSTANT' } } })
    gates[1].resolve({ data: { data: { fileId: 2, mode: 'INSTANT' } } })
    await vi.waitFor(() => expect(initUpload).toHaveBeenCalledTimes(4))
    gates[2].resolve({ data: { data: { fileId: 3, mode: 'INSTANT' } } })
    gates[3].resolve({ data: { data: { fileId: 4, mode: 'INSTANT' } } })
    await flushAsync()
  })

  it('starts the next pending upload after canceling an active upload', async () => {
    const gates = Array.from({ length: 4 }, () => deferred<{ data: { data: { fileId: number; mode: 'INSTANT' } } }>())
    let instantIndex = 0
    initUpload.mockImplementation(() => gates[instantIndex++].promise)
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
    await vi.waitFor(() => expect(initUpload).toHaveBeenCalledTimes(4))

    expect(store.queue[0]).toMatchObject({ status: 'canceled' })
    expect(store.queue[3]).toMatchObject({ fileName: 'd.txt', status: 'hashing' })
  })

  it('pauses and resumes an active upload before chunk transfer', async () => {
    const gate = deferred<{ data: { data: { chunkNumber: number } } }>()
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'pause-session', totalChunks: 1 } } })
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
    initUpload.mockResolvedValue({ data: { data: { fileId: 1, mode: 'INSTANT' } } })

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await flushAsync()

    expect(store.queue[0]).toMatchObject({ status: 'instant', progress: 100 })
    expect(initUpload).toHaveBeenCalledTimes(1)
    expect(uploadChunk).not.toHaveBeenCalled()
    expect(getUploadStatus).not.toHaveBeenCalled()
    expect(refresh).toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalledWith('demo.txt 秒传成功')
  })

  it('starts multipart upload from a single initialization request', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'session-1', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 2 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initUpload).toHaveBeenCalledWith(10, 'demo.txt', 3, expect.any(String))
    expect(uploadChunk).toHaveBeenCalledWith('session-1', 0, expect.any(Blob), expect.any(Function))
    expect(completeMultipart).toHaveBeenCalledWith('session-1')
    expect(saveResumableUpload).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({
      sessionId: 'session-1',
      parentId: 10,
      fileName: 'demo.txt',
      displayName: 'demo.txt',
    }))
    expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'session-1')
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100, chunkProgress: '' })
    expect(toastError).not.toHaveBeenCalledWith(expect.stringContaining('no matching'))
  })

  it('completes zero-byte uploads without sending chunks', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'empty-session', totalChunks: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 9 } } })
    const refresh = mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile(0, 'empty.txt')], null)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initUpload).toHaveBeenCalledWith(null, 'empty.txt', 0, expect.any(String))
    expect(uploadChunk).not.toHaveBeenCalled()
    expect(saveResumableUpload).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ sessionId: 'empty-session', totalChunks: 0 }))
    expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'empty-session')
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100, chunkProgress: '' })
    expect(refresh).toHaveBeenCalled()
  })

  it('uploads every chunk and records progress for larger files', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'session-large', totalChunks: 2 } } })
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

    expect(initUpload).toHaveBeenCalledWith(10, 'large.bin', 6 * 1024 * 1024, expect.any(String))
    expect(uploadChunk).toHaveBeenCalledTimes(2)
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100 })
  })

  it('allows root uploads with null parent id', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'root-session', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 4 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile()], null)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initUpload).toHaveBeenCalledWith(null, 'demo.txt', 3, expect.any(String))
    expect(store.queue[0]).toMatchObject({ status: 'done', progress: 100 })
  })

  it('uploads entries to their own target folders and keeps display names', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'nested-session', totalChunks: 1 } } })
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

    expect(initUpload).toHaveBeenCalledWith(42, 'demo.txt', 3, expect.any(String))
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
    getUploadStatus.mockResolvedValue({ data: { data: { totalChunks: 3, uploadedChunks: 1, missingChunks: [1, 2] } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 1 } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 6 } } })
    mockFilesRefresh()

    const store = useUploadStore()
    store.addFiles([makeFile(11 * 1024 * 1024, 'large.bin')], 10)
    await vi.waitFor(() => expect(completeMultipart).toHaveBeenCalled())
    await flushAsync()

    expect(initUpload).not.toHaveBeenCalled()
    expect(uploadChunk).toHaveBeenCalledTimes(2)
    expect(uploadChunk.mock.calls.map(call => call[1])).toEqual([1, 2])
    expect(completeMultipart).toHaveBeenCalledWith('resume-session')
    expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'resume-session')
    expect(store.queue[0]).toMatchObject({ uploadId: 'resume-session', status: 'done' })
  })

  it('reinitializes an expired session once and accepts an instant result', async () => {
    findResumableUpload.mockReturnValue({
      sessionId: 'expired', parentId: 10, fileName: 'demo.txt', displayName: 'demo.txt',
      fileSize: 3, md5Hash: expect.any(String), totalChunks: 1, chunkSize: 5242880,
      createdAt: Date.now(), updatedAt: Date.now(),
    })
    getUploadStatus.mockRejectedValueOnce({ response: { data: { code: 419006 } } })
    initUpload.mockResolvedValue({ data: { data: { mode: 'INSTANT', fileId: 88 } } })
    mockFilesRefresh()
    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(store.queue[0]?.status).toBe('instant'))
    expect(initUpload).toHaveBeenCalledTimes(1)
    expect(getUploadStatus).toHaveBeenCalledTimes(1)
    expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'expired')
    expect(uploadChunk).not.toHaveBeenCalled()
    expect(completeMultipart).not.toHaveBeenCalled()
  })

  it('keeps the zero-byte session when its completion response is lost', async () => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', sessionId: 'empty-session', chunkSize: 5242880, totalChunks: 0 } } })
    completeMultipart.mockRejectedValueOnce(new Error('connection lost'))
    const store = useUploadStore()
    store.addFiles([makeFile(0, 'empty.txt')], 10)
    await vi.waitFor(() => expect(store.queue[0]?.status).toBe('error'))
    expect(saveResumableUpload).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ sessionId: 'empty-session', totalChunks: 0 }))
    expect(removeResumableUpload).not.toHaveBeenCalledWith(expect.any(Object), 'empty-session')
    expect(uploadChunk).not.toHaveBeenCalled()
    findResumableUpload.mockReturnValue(saveResumableUpload.mock.calls[0]![1])
    getUploadStatus.mockResolvedValue({ data: { data: { totalChunks: 0, uploadedChunks: 0, missingChunks: [] } } })
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 89 } } })
    mockFilesRefresh()
    store.retryUpload(store.queue[0]!.id)
    await vi.waitFor(() => expect(store.queue[0]?.status).toBe('done'))
    expect(initUpload).toHaveBeenCalledTimes(1)
    expect(completeMultipart).toHaveBeenCalledTimes(2)
    expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'empty-session')
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
    store.restoreResumableUploads()
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
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'failed-session', totalChunks: 1 } } })
    uploadChunk.mockRejectedValue(new Error('network down'))

    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith('demo.txt 上传失败'))

    expect(saveResumableUpload).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ sessionId: 'failed-session' }))
    expect(removeResumableUpload).not.toHaveBeenCalledWith(expect.any(Object), 'failed-session')
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
  it.each([419006, 419007, 419016, 419017])('handles integrity error %s without an automatic retry', async code => {
    initUpload.mockResolvedValue({ data: { data: { mode: 'MULTIPART', chunkSize: 5 * 1024 * 1024, sessionId: 'failed-session', totalChunks: 1 } } })
    uploadChunk.mockResolvedValue({ data: { data: { chunkNumber: 0 } } })
    completeMultipart.mockRejectedValueOnce({ response: { data: { code } } })
    abortMultipart.mockResolvedValue({})
    mockFilesRefresh()
    const store = useUploadStore()
    store.addFiles([makeFile()], 10)
    await vi.waitFor(() => expect(store.queue[0]?.status).toBe('error'))
    expect(completeMultipart).toHaveBeenCalledTimes(1)
    expect(store.queue[0].uploadId).toBe(code === 419007 ? 'failed-session' : undefined)
    expect(store.queue[0].resumable).toBe(code === 419007)
    expect(abortMultipart).toHaveBeenCalledTimes(code === 419007 ? 0 : 1)
    if (code !== 419007) expect(removeResumableUpload).toHaveBeenCalledWith(expect.any(Object), 'failed-session')
    completeMultipart.mockResolvedValue({ data: { data: { fileId: 9 } } })
    store.retryUpload(store.queue[0].id)
    await vi.waitFor(() => expect(store.queue[0].status).toBe('done'))
  })

})
