import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const mocks = vi.hoisted(() => ({ preview: vi.fn(), invoke: vi.fn() }))
vi.mock('@/api/download', () => ({ previewFile: mocks.preview }))
vi.mock('@/platform/desktop', () => ({ invoke: mocks.invoke }))
import { usePreviewStore } from '@/stores/preview'
import { invalidateIdentity } from '@/platform/identity'
beforeEach(() => { setActivePinia(createPinia()); delete window.__BCD_RUNTIME__; mocks.preview.mockReset(); mocks.invoke.mockReset() })
it('uses the browser preview for video without loading a native player', async () => {
  mocks.preview.mockResolvedValue({ data: new Blob(['web-video'], { type: 'video/webm' }) })
  const store = usePreviewStore()
  await store.openPreview({ id: 4, mimeType: 'application/octet-stream', fileName: 'sample.webm' } as any)
  expect(store.open).toBe(true); expect(store.url).toBe('blob:mock-url')
  expect(mocks.invoke).not.toHaveBeenCalled()
  expect(store.target?.mimeType).toBe('video/webm')
  expect((vi.mocked(URL.createObjectURL).mock.calls.at(-1)![0] as Blob).type).toBe('video/webm')
  store.closePreview(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
})
it('renders uploaded HTML as source and discards it on Web logout', async () => {
  mocks.preview.mockResolvedValue({ data: new Blob(['<script>bad()</script>'], { type: 'text/html' }) })
  const store = usePreviewStore()
  await store.openPreview({ id: 3, mimeType: 'text/html', fileName: 'sample.html' } as any)
  expect(store.target?.mimeType).toBe('text/plain')
  expect((vi.mocked(URL.createObjectURL).mock.calls.at(-1)![0] as Blob).type).toBe('text/plain;charset=utf-8')
  invalidateIdentity('logout')
  expect(store.open).toBe(false); expect(store.url).toBe('')
})
