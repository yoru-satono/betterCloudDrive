import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { toast } from 'vue-sonner'
import {
  cancelTransferNode,
  clearFinishedTransfers,
  getTransferQueue,
  isDesktopTransferRuntime,
  pauseTransferNode,
  registerTransferListeners,
  restoreDesktopTransfers,
  resumeTransferNode,
  type TransferDirection,
  type TransferQueue,
  type TransferTask,
} from '@/api/desktopTransfer'

export const useTransferStore = defineStore('transfer', () => {
  const queue = ref<TransferQueue>({ tasks: [] })
  const isOpen = ref(false)
  const activeTab = ref<TransferDirection>('upload')
  let listenersReady = false

  const tasks = computed(() => queue.value?.tasks ?? [])
  const uploadTasks = computed(() => tasks.value.filter(task => task.direction === 'upload'))
  const downloadTasks = computed(() => tasks.value.filter(task => task.direction === 'download'))
  const visibleTasks = computed(() => activeTab.value === 'upload' ? uploadTasks.value : downloadTasks.value)
  const activeCount = computed(() => tasks.value.filter(task => isActive(task.status)).length)
  const allDone = computed(() =>
    tasks.value.length > 0
    && tasks.value.every(task => isFinished(task.status)),
  )

  async function initialize() {
    if (!isDesktopTransferRuntime()) return
    await ensureListeners()
    try {
      queue.value = normalizeQueue(await getTransferQueue())
      if (queue.value.tasks.length) isOpen.value = true
      queue.value = normalizeQueue(await restoreDesktopTransfers())
      if (queue.value.tasks.length) isOpen.value = true
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '恢复传输队列失败')
    }
  }

  async function ensureListeners() {
    if (listenersReady || !isDesktopTransferRuntime()) return
    listenersReady = true
    try {
      await registerTransferListeners({
        onQueueUpdated: next => {
          queue.value = normalizeQueue(next)
          if (queue.value.tasks.length) isOpen.value = true
        },
        onError: message => toast.error(message),
      })
    } catch (error) {
      listenersReady = false
      toast.error(error instanceof Error ? error.message : '初始化传输队列失败')
    }
  }

  function applyQueue(next: TransferQueue) {
    queue.value = normalizeQueue(next)
    if (queue.value.tasks.length) isOpen.value = true
  }

  async function pauseNode(nodeId: string) {
    applyQueue(await pauseTransferNode(nodeId))
  }

  async function resumeNode(nodeId: string) {
    applyQueue(await resumeTransferNode(nodeId))
  }

  async function cancelNode(nodeId: string) {
    applyQueue(await cancelTransferNode(nodeId))
  }

  async function clearDone() {
    if (!isDesktopTransferRuntime()) {
      queue.value = {
        tasks: tasks.value.filter(task => !isFinished(task.status)),
      }
      return
    }
    applyQueue(await clearFinishedTransfers())
  }

  return {
    queue,
    isOpen,
    activeTab,
    uploadTasks,
    downloadTasks,
    visibleTasks,
    activeCount,
    allDone,
    initialize,
    ensureListeners,
    applyQueue,
    pauseNode,
    resumeNode,
    cancelNode,
    clearDone,
  }
})

function normalizeQueue(queue: TransferQueue | undefined | null): TransferQueue {
  return queue && Array.isArray(queue.tasks) ? queue : { tasks: [] }
}

export function isActive(status: TransferTask['status']) {
  return status === 'pending' || status === 'hashing' || status === 'transferring'
}

export function isFinished(status: TransferTask['status']) {
  return status === 'done' || status === 'instant' || status === 'error' || status === 'canceled'
}
