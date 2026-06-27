import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { ensureFreshAccessToken } from './client'
import { getApiBaseUrl, isDesktopRuntime } from '@/config/runtime'
import {
  readResumableDirectories,
  readResumableUploads,
} from './resumableUpload'

export type TransferDirection = 'upload' | 'download'
export type TransferNodeKind = 'file' | 'folder'
export type TransferStatus = 'pending' | 'hashing' | 'transferring' | 'paused' | 'done' | 'instant' | 'error' | 'canceled'

export interface TransferNode {
  id: string
  taskId: string
  parentId: string | null
  direction: TransferDirection
  kind: TransferNodeKind
  name: string
  displayPath: string
  path: string[]
  status: TransferStatus
  progress: number
  bytesDone: number
  bytesTotal: number
  error: string | null
  localPath: string | null
  targetPath: string | null
  remoteFileId: number | null
  remoteParentId: number | null
  uploadId: string | null
  md5Hash: string | null
  totalChunks: number
  completedChunks: number
  chunkSize: number
}

export interface TransferTask {
  id: string
  direction: TransferDirection
  rootNodeId: string
  name: string
  status: TransferStatus
  progress: number
  bytesDone: number
  bytesTotal: number
  createdAt: number
  updatedAt: number
  apiBaseUrl: string
  baseParentId: number | null
  nodes: TransferNode[]
}

export interface TransferQueue {
  tasks: TransferTask[]
}

export const EMPTY_TRANSFER_QUEUE: TransferQueue = { tasks: [] }

export const isDesktopTransferRuntime = () => isDesktopRuntime()

export function getTransferToken() {
  return localStorage.getItem('accessToken') || ''
}

export async function getTransferQueue() {
  if (!isDesktopTransferRuntime()) return EMPTY_TRANSFER_QUEUE
  return invoke<TransferQueue>('get_transfer_queue')
}

export async function restoreDesktopTransfers() {
  if (!isDesktopTransferRuntime()) return EMPTY_TRANSFER_QUEUE
  const token = await ensureFreshAccessToken()
  return invoke<TransferQueue>('restore_desktop_transfers', {
    request: {
      token: token || '',
      apiBaseUrl: getApiBaseUrl(),
      resumableUploads: readResumableUploads(),
      resumableDirectories: readResumableDirectories(),
    },
  })
}

export async function pauseTransferNode(nodeId: string) {
  return invoke<TransferQueue>('pause_transfer_node', { nodeId })
}

export async function resumeTransferNode(nodeId: string) {
  const token = await ensureFreshAccessToken()
  return invoke<TransferQueue>('resume_transfer_node', {
    nodeId,
    token: token || '',
    apiBaseUrl: getApiBaseUrl(),
  })
}

export async function cancelTransferNode(nodeId: string) {
  return invoke<TransferQueue>('cancel_transfer_node', { nodeId })
}

export async function clearFinishedTransfers() {
  if (!isDesktopTransferRuntime()) return EMPTY_TRANSFER_QUEUE
  return invoke<TransferQueue>('clear_finished_transfers')
}

export async function registerTransferListeners(handlers: {
  onQueueUpdated: (queue: TransferQueue) => void
  onError: (message: string) => void
}) {
  if (!isDesktopTransferRuntime()) return () => undefined
  const unlisten = await Promise.all([
    listen<TransferQueue>('transfer:queue-updated', event => handlers.onQueueUpdated(event.payload)),
    listen<{ message?: string }>('transfer:error', event => handlers.onError(event.payload.message || '传输失败')),
  ])
  return () => {
    unlisten.forEach((dispose: UnlistenFn) => dispose())
  }
}
