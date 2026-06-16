import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TransferQueue from '@/components/file/TransferQueue.vue'
import { useTransferStore } from '@/stores/transfer'

vi.mock('@/api/desktopTransfer', async () => {
  const actual = await vi.importActual<typeof import('@/api/desktopTransfer')>('@/api/desktopTransfer')
  return {
    ...actual,
    isDesktopTransferRuntime: () => true,
    getTransferQueue: vi.fn(() => Promise.resolve({ tasks: [] })),
    restoreDesktopTransfers: vi.fn(() => Promise.resolve({ tasks: [] })),
    registerTransferListeners: vi.fn(() => Promise.resolve(() => undefined)),
  }
})

describe('TransferQueue', () => {
  it('renders upload and download tabs with transfer progress', () => {
    setActivePinia(createPinia())
    const store = useTransferStore()
    store.isOpen = true
    store.activeTab = 'download'
    store.queue = {
      tasks: [{
        id: 'task-1',
        direction: 'download',
        rootNodeId: 'root',
        name: 'docs',
        status: 'transferring',
        progress: 50,
        bytesDone: 5,
        bytesTotal: 10,
        createdAt: 1,
        updatedAt: 1,
        apiBaseUrl: '',
        baseParentId: null,
        nodes: [{
          id: 'root',
          taskId: 'task-1',
          parentId: null,
          direction: 'download',
          kind: 'folder',
          name: 'docs',
          displayPath: 'docs',
          path: ['docs'],
          status: 'transferring',
          progress: 50,
          bytesDone: 5,
          bytesTotal: 10,
          error: null,
          localPath: null,
          targetPath: 'D:/Downloads/docs',
          remoteFileId: 1,
          remoteParentId: null,
          uploadId: null,
          md5Hash: null,
          totalChunks: 0,
          completedChunks: 0,
          chunkSize: 0,
        }],
      }],
    }

    const wrapper = mount(TransferQueue)

    expect(wrapper.text()).toContain('上传 0')
    expect(wrapper.text()).toContain('下载 1')
    expect(wrapper.text()).toContain('docs')
    expect(wrapper.text()).toContain('50.00%')
  })
})
