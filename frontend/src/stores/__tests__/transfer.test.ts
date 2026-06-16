import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTransferStore } from '@/stores/transfer'
import * as desktopTransfer from '@/api/desktopTransfer'

vi.mock('@/api/desktopTransfer', () => ({
  isDesktopTransferRuntime: vi.fn(() => true),
  getTransferQueue: vi.fn(),
  restoreDesktopTransfers: vi.fn(),
  registerTransferListeners: vi.fn(),
  pauseTransferNode: vi.fn(),
  resumeTransferNode: vi.fn(),
  cancelTransferNode: vi.fn(),
  clearFinishedTransfers: vi.fn(),
}))

const getTransferQueue = vi.mocked(desktopTransfer.getTransferQueue)
const restoreDesktopTransfers = vi.mocked(desktopTransfer.restoreDesktopTransfers)
const registerTransferListeners = vi.mocked(desktopTransfer.registerTransferListeners)
const pauseTransferNode = vi.mocked(desktopTransfer.pauseTransferNode)
const resumeTransferNode = vi.mocked(desktopTransfer.resumeTransferNode)
const cancelTransferNode = vi.mocked(desktopTransfer.cancelTransferNode)
const clearFinishedTransfers = vi.mocked(desktopTransfer.clearFinishedTransfers)

const emptyQueue = { tasks: [] }
const downloadQueue = {
  tasks: [{
    id: 'task-1',
    direction: 'download' as const,
    rootNodeId: 'node-1',
    name: 'demo.bin',
    status: 'transferring' as const,
    progress: 50,
    bytesDone: 5,
    bytesTotal: 10,
    createdAt: 1,
    updatedAt: 1,
    apiBaseUrl: '',
    baseParentId: null,
    nodes: [],
  }],
}

beforeEach(() => {
  setActivePinia(createPinia())
  getTransferQueue.mockReset().mockResolvedValue(emptyQueue)
  restoreDesktopTransfers.mockReset().mockResolvedValue(downloadQueue)
  registerTransferListeners.mockReset().mockResolvedValue(() => undefined)
  pauseTransferNode.mockReset().mockResolvedValue(downloadQueue)
  resumeTransferNode.mockReset().mockResolvedValue(downloadQueue)
  cancelTransferNode.mockReset().mockResolvedValue(emptyQueue)
  clearFinishedTransfers.mockReset().mockResolvedValue(emptyQueue)
})

describe('transfer store', () => {
  it('restores the desktop transfer queue on initialization', async () => {
    const store = useTransferStore()

    await store.initialize()

    expect(registerTransferListeners).toHaveBeenCalled()
    expect(getTransferQueue).toHaveBeenCalled()
    expect(restoreDesktopTransfers).toHaveBeenCalled()
    expect(store.queue.tasks).toHaveLength(1)
    expect(store.isOpen).toBe(true)
  })

  it('maps node controls to Tauri commands', async () => {
    const store = useTransferStore()

    await store.pauseNode('node-1')
    await store.resumeNode('node-1')
    await store.cancelNode('node-1')

    expect(pauseTransferNode).toHaveBeenCalledWith('node-1')
    expect(resumeTransferNode).toHaveBeenCalledWith('node-1')
    expect(cancelTransferNode).toHaveBeenCalledWith('node-1')
  })

  it('clears finished transfers through Tauri persistence', async () => {
    const store = useTransferStore()

    await store.clearDone()

    expect(clearFinishedTransfers).toHaveBeenCalled()
    expect(store.queue.tasks).toHaveLength(0)
  })
})
