<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import type { TransferNode, TransferTask } from '@/api/desktopTransfer'
import { isActive, isFinished, useTransferStore } from '@/stores/transfer'

const props = defineProps<{
  task: TransferTask
  node: TransferNode
  depth: number
  statusLabel: Record<string, string>
  childrenOf: (task: TransferTask, nodeId: string | null) => TransferNode[]
  isExpanded: (nodeId: string) => boolean
  toggleExpanded: (nodeId: string) => void
  formatProgress: (progress: number) => string
  formatBytes: (bytes: number) => string
  canPause: (node: TransferNode) => boolean
  canResume: (node: TransferNode) => boolean
}>()

const store = useTransferStore()
</script>

<template>
  <div>
    <div class="transfer-node" :style="{ paddingLeft: `${8 + depth * 14}px` }">
      <div class="transfer-node__main">
        <button
          v-if="node.kind === 'folder'"
          type="button"
          class="transfer-node__toggle"
          @click="toggleExpanded(node.id)"
        >
          {{ isExpanded(node.id) ? '▾' : '▸' }}
        </button>
        <span v-else class="transfer-node__toggle transfer-node__toggle--empty" />

        <div class="transfer-node__body">
          <div class="transfer-node__name" :title="node.displayPath">{{ node.name }}</div>
          <div class="transfer-node__meta">
            <span class="transfer-node__status" :class="`transfer-node__status--${node.status}`">
              {{ statusLabel[node.status] }}
            </span>
            <span class="font-mono">{{ formatProgress(node.progress) }}%</span>
            <span v-if="node.bytesTotal" class="font-mono">
              {{ formatBytes(node.bytesDone) }} / {{ formatBytes(node.bytesTotal) }}
            </span>
          </div>
          <div v-if="isActive(node.status)" class="transfer-node__bar">
            <div class="transfer-node__bar-fill" :style="{ width: `${node.progress}%` }" />
          </div>
          <p v-if="node.error" class="transfer-node__error">{{ node.error }}</p>
        </div>
      </div>

      <div class="transfer-node__actions">
        <OButton v-if="canPause(node)" variant="subtle" size="sm" @click="store.pauseNode(node.id)">暂停</OButton>
        <OButton v-if="canResume(node)" variant="subtle" size="sm" @click="store.resumeNode(node.id)">继续</OButton>
        <OButton v-if="!isFinished(node.status)" variant="danger" size="sm" @click="store.cancelNode(node.id)">取消</OButton>
      </div>
    </div>

    <template v-if="node.kind === 'folder' && isExpanded(node.id)">
      <TransferNodeRow
        v-for="child in childrenOf(task, node.id)"
        :key="child.id"
        :task="task"
        :node="child"
        :depth="depth + 1"
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
</template>

<style scoped>
.transfer-node {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px;
  padding: 8px; border-radius: 7px; background: var(--bg-elevated);
}
.transfer-node__main { display: flex; gap: 6px; min-width: 0; }
.transfer-node__toggle {
  width: 16px; height: 20px; border: 0; background: transparent;
  color: var(--text-muted); cursor: pointer; padding: 0; flex-shrink: 0;
}
.transfer-node__toggle--empty { display: inline-block; cursor: default; }
.transfer-node__body { min-width: 0; flex: 1; }
.transfer-node__name {
  font-size: 12px; color: var(--text-primary); margin-bottom: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.transfer-node__meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  color: var(--text-secondary); font-size: 11px; margin-bottom: 4px;
}
.transfer-node__status--done,
.transfer-node__status--instant { color: var(--success); }
.transfer-node__status--canceled,
.transfer-node__status--error { color: var(--danger); }
.transfer-node__status--transferring,
.transfer-node__status--hashing { color: var(--accent); }
.transfer-node__status--paused { color: var(--warning); }
.transfer-node__bar {
  height: 3px; background: var(--border); border-radius: 2px; overflow: hidden;
}
.transfer-node__bar-fill {
  height: 100%; background: var(--accent); border-radius: 2px; transition: width 200ms;
}
.transfer-node__actions {
  display: flex; align-items: flex-start; gap: 4px; flex-wrap: wrap; justify-content: flex-end;
}
.transfer-node__error { font-size: 11px; color: var(--danger); margin-top: 3px; }
</style>
