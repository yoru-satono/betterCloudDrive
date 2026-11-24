import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const mocks = vi.hoisted(() => ({ preview: vi.fn(), personal: vi.fn(), team: vi.fn(), invoke: vi.fn() }))
vi.mock('@/api/download', () => ({ previewFile: mocks.preview }))
vi.mock('@/api/office', () => ({ getOfficeConfig: mocks.personal, getTeamOfficeConfig: mocks.team }))
vi.mock('@/platform/desktop', () => ({ invoke: mocks.invoke }))
import { usePreviewStore } from '@/stores/preview'
import { invalidateIdentity } from '@/platform/identity'
const deferred = () => { let resolve!: (value: any) => void, reject!: (e: Error) => void; const promise = new Promise<any>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const file = (id = 1, mimeType = 'image/png', fileName = 'sample.png') => ({ id, mimeType, fileName } as any)
let store: ReturnType<typeof usePreviewStore>
beforeEach(() => {
  setActivePinia(createPinia()); store = usePreviewStore()
  mocks.preview.mockReset(); mocks.personal.mockReset(); mocks.team.mockReset(); mocks.invoke.mockReset().mockResolvedValue(null)
  Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:fixture'), configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
})
afterEach(() => store.closePreview())
it('discards an image returned after close, without allocating a Blob URL', async () => {
  const request = deferred(); mocks.preview.mockReturnValue(request.promise)
  const opened = store.openPreview(file()); store.closePreview()
  request.resolve({ data: new Blob(['old']) }); await opened
  expect(store.open).toBe(false); expect(URL.createObjectURL).not.toHaveBeenCalled()
})
it('keeps the latest image when responses arrive out of order and revokes it on close', async () => {
  const old = deferred(); mocks.preview.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: new Blob(['new']) })
  const opened = store.openPreview(file()); await store.openPreview(file(2))
  old.resolve({ data: new Blob(['old']) }); await opened
  expect(store.target?.id).toBe(2); expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  store.closePreview(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:fixture')
})
it.each([['text/html', 'source.html'], ['application/xml', 'source.xml']])('turns uploaded %s into plain text and revokes its URL on identity change', async (mime, name) => {
  mocks.preview.mockResolvedValue({ data: new Blob(['<script>bad()</script>'], { type: 'text/html' }) })
  await store.openPreview(file(1, mime, name))
  expect(store.target?.mimeType).toBe('text/plain')
  const blob = vi.mocked(URL.createObjectURL).mock.calls[0]![0] as Blob
  expect(blob.type).toBe('text/plain;charset=utf-8')
  invalidateIdentity('logout'); expect(store.open).toBe(false); expect(store.url).toBe('')
})
it('discards Office config after switching file or identity', async () => {
  const old = deferred(); mocks.team.mockReturnValue(old.promise); mocks.preview.mockResolvedValue({ data: new Blob(['new']) })
  const opened = store.openPreview(file(1, 'application/docx', 'a.docx'), { teamId: 9 })
  await store.openPreview(file(2)); old.resolve({ data: { data: { config: {} } } }); await opened
  expect(store.officeConfig).toBeNull(); expect(store.target?.id).toBe(2)
  const late = deferred(); mocks.personal.mockReturnValue(late.promise)
  const next = store.openPreview(file(3, 'application/docx', 'b.docx')); invalidateIdentity('server')
  late.resolve({ data: { data: { config: {} } } }); await next
  expect(store.open).toBe(false); expect(store.officeConfig).toBeNull()
})
it('recovers an Office config failure through a fresh authorized request', async () => {
  mocks.personal.mockRejectedValueOnce(new Error('synthetic')).mockResolvedValueOnce({ data: { data: { config: {} } } })
  await store.openPreview(file(1, 'application/docx', 'a.docx')); expect(store.error).not.toBe('')
  await store.refreshOfficeConfig(); expect(store.error).toBe(''); expect(store.officeConfig).not.toBeNull()
})
it('keeps the web modal independent while native video opens with IDs and locale only', async () => {
  mocks.preview.mockResolvedValue({ data: new Blob(['image']) }); await store.openPreview(file())
  await store.openPreview(file(4, 'video/mp4', 'v.mp4'), { teamId: 9 })
  expect(store.open).toBe(true); expect(store.target?.id).toBe(1)
  expect(mocks.invoke).toHaveBeenCalledWith('media:open', expect.objectContaining({ fileId: 4, teamId: 9, locale: expect.any(String) }))
  expect(Object.keys(mocks.invoke.mock.calls[0]![1]).sort()).toEqual(['fileId', 'fileName', 'locale', 'teamId'])
})
