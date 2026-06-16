<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import TransferNodeRow from './TransferNodeRow.vue'
import { isDesktopTransferRuntime, type TransferNode, type TransferTask } from '@/api/desktopTransfer'
import { useTransferStore } from '@/stores/transfer'
import { useUploadStore, type UploadItem } from '@/stores/upload'

const store = useTransferStore()
const uploadStore = useUploadStore()
const expanded = ref<Set<string>>(new Set())

const statusLabel: Record<string, string> = {
  pending: '等待中',
  hashing: '计算中',
  transferring: '传输中',
  paused: '已暂停',
  done: '完成',
  instant: '秒传',
  error: '失败',
  canceled: '已取消',
}

const title = computed(() => {
  const active = store.activeCount + uploadStore.queue.filter(item => ['pending', 'hashing', 'uploading'].includes(item.status)).length
  if (active > 0) return `传输中 (${active})`
  if (store.allDone && uploadStore.queue.every(item => ['done', 'instant', 'error', 'canceled'].includes(item.status))) return '全部完成'
  return '传输队列'
})

const browserUploadItems = computed(() => uploadStore.queue.filter(item => !item.desktop))
const uploadCount = computed(() => store.uploadTasks.length + browserUploadItems.value.length)
const transferTasks = computed(() => store.queue?.tasks ?? [])
const hasVisibleItems = computed(() => transferTasks.value.length > 0 || browserUploadItems.value.length > 0)
const isQueueOpen = computed(() => store.isOpen || uploadStore.isOpen)

onMounted(() => {
  store.initialize()
})

function rootNode(task: TransferTask) {
  return task.nodes.find(node => node.id === task.rootNodeId) || task.nodes[0]
}

function childrenOf(task: TransferTask, nodeId: string | null) {
  return task.nodes.filter(node => node.parentId === nodeId)
}

function isExpanded(nodeId: string) {
  return expanded.value.has(nodeId)
}

function toggleExpanded(nodeId: string) {
  const next = new Set(expanded.value)
  if (next.has(nodeId)) next.delete(nodeId)
  else next.add(nodeId)
  expanded.value = next
}

function formatProgress(progress: number) {
  return progress.toFixed(2)
}

function formatBytes(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

function canPause(node: TransferNode) {
  return node.status === 'pending' || node.status === 'hashing' || node.status === 'transferring'
}

function canResume(node: TransferNode) {
  return node.status === 'paused' || node.status === 'error'
}

function uploadStatusLabel(item: UploadItem) {
  return statusLabel[item.status === 'uploading' ? 'transferring' : item.status === 'resume_required' ? 'error' : item.status] || item.status
}

async function clearFinished() {
  await store.clearDone()
  uploadStore.clearDone()
}
</script>

<template>
  <Transition name="slide-up-queue">
    <div v-if="isDesktopTransferRuntime() && isQueueOpen && hasVisibleItems" class="transfer-queue">
      <div class="transfer-queue__header">
        <span class="transfer-queue__title">{{ title }}</span>
        <div class="transfer-queue__header-actions">
          <OButton v-if="store.allDone || uploadStore.queue.some(item => ['done', 'instant', 'error', 'canceled'].includes(item.status))" variant="subtle" size="sm" @click="clearFinished">清除</OButton>
          <OButton variant="subtle" size="sm" :icon="true" @click="store.isOpen = false; uploadStore.isOpen = false">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </OButton>
        </div>
      </div>

      <div class="transfer-queue__tabs">
        <button
          type="button"
          class="transfer-queue__tab"
          :class="{ 'transfer-queue__tab--active': store.activeTab === 'upload' }"
          @click="store.activeTab = 'upload'"
        >
          上传 {{ uploadCount }}
        </button>
        <button
          type="button"
          class="transfer-queue__tab"
          :class="{ 'transfer-queue__tab--active': store.activeTab === 'download' }"
          @click="store.activeTab = 'download'"
        >
          下载 {{ store.downloadTasks.length }}
        </button>
      </div>

      <div class="transfer-queue__list">
        <div v-if="store.visibleTasks.length === 0 && (store.activeTab !== 'upload' || browserUploadItems.length === 0)" class="transfer-queue__empty">暂无传输</div>
        <template v-if="store.activeTab === 'upload'">
          <div
            v-for="item in browserUploadItems"
            :key="item.id"
            class="transfer-upload"
          >
            <div class="transfer-upload__body">
              <div class="transfer-upload__name" :title="item.displayName || item.fileName">{{ item.displayName || item.fileName }}</div>
              <div class="transfer-upload__meta">
                <span>{{ uploadStatusLabel(item) }}</span>
                <span class="font-mono">{{ formatProgress(item.progress) }}%</span>
                <span v-if="item.chunkProgress" class="font-mono">{{ item.chunkProgress }}</span>
              </div>
              <div v-if="['hashing', 'uploading'].includes(item.status)" class="transfer-upload__bar">
                <div class="transfer-upload__bar-fill" :style="{ width: `${item.progress}%` }" />
              </div>
              <p v-if="item.error" class="transfer-upload__error">{{ item.error }}</p>
            </div>
            <div class="transfer-upload__actions">
              <OButton v-if="['pending', 'hashing', 'uploading'].includes(item.status)" variant="subtle" size="sm" @click="uploadStore.pauseUpload(item.id)">暂停</OButton>
              <OButton v-if="item.status === 'paused'" variant="subtle" size="sm" @click="uploadStore.resumePausedUpload(item.id)">继续</OButton>
              <OButton v-if="!['done', 'instant', 'canceled'].includes(item.status)" variant="danger" size="sm" @click="uploadStore.cancelUpload(item.id)">取消</OButton>
            </div>
          </div>
        </template>
        <template v-for="task in store.visibleTasks" :key="task.id">
          <TransferNodeRow
            v-if="rootNode(task)"
            :task="task"
            :node="rootNode(task)!"
            :depth="0"
            :status-label="statusLabel"
            :children-of="childrenOf"
            :is-expanded="isExpanded"
            :toggle-expanded="toggleExpanded"
            :format-progress="formatProgress"
            :format-bytes="formatBytes"
            :can-pause="canPause"
            :can-resume="canResume"
          />
        </template>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.transfer-queue {
  position: fixed; bottom: 20px; right: 20px; z-index: 150;
  width: 420px;
  background: var(--bg-overlay);
  border: 1px solid var(--border-hover);
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.5);
  overflow: hidden;
}
.transfer-queue__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-elevated);
}
.transfer-queue__title { font-size: 13px; font-weight: 500; }
.transfer-queue__header-actions { display: flex; gap: 4px; }
.transfer-queue__tabs {
  display: grid; grid-template-columns: 1fr 1fr; gap: 4px;
  padding: 8px; border-bottom: 1px solid var(--border);
}
.transfer-queue__tab {
  height: 28px; border: 1px solid transparent; border-radius: 7px;
  background: transparent; color: var(--text-secondary); cursor: pointer;
  font-family: var(--font-sans); font-size: 12px;
}
.transfer-queue__tab--active {
  background: var(--accent-dim); color: var(--accent); border-color: rgba(0,212,170,0.15);
}
.transfer-queue__list {
  max-height: 340px; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 4px;
}
.transfer-queue__empty { padding: 16px; text-align: center; color: var(--text-muted); font-size: 12px; }
.transfer-upload {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px;
  padding: 8px; border-radius: 7px; background: var(--bg-elevated);
}
.transfer-upload__body { min-width: 0; }
.transfer-upload__name {
  font-size: 12px; color: var(--text-primary); margin-bottom: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.transfer-upload__meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  color: var(--text-secondary); font-size: 11px; margin-bottom: 4px;
}
.transfer-upload__bar {
  height: 3px; background: var(--border); border-radius: 2px; overflow: hidden;
}
.transfer-upload__bar-fill {
  height: 100%; background: var(--accent); border-radius: 2px; transition: width 200ms;
}
.transfer-upload__actions {
  display: flex; align-items: flex-start; gap: 4px; flex-wrap: wrap; justify-content: flex-end;
}
.transfer-upload__error { font-size: 11px; color: var(--danger); margin-top: 3px; }
.slide-up-queue-enter-active { transition: all 220ms var(--ease-out); }
.slide-up-queue-leave-active { transition: all 150ms; }
.slide-up-queue-enter-from { opacity: 0; transform: translateY(12px) scale(0.98); }
.slide-up-queue-leave-to   { opacity: 0; transform: translateY(6px); }
</style>
