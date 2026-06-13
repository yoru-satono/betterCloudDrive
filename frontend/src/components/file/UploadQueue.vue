<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import { useUploadStore } from '@/stores/upload'
import { computed } from 'vue'

const store = useUploadStore()

const activeCount = computed(() => store.queue.filter(i => i.status === 'uploading' || i.status === 'pending' || i.status === 'hashing').length)
const allDone = computed(() => store.queue.length > 0 && store.queue.every(i => i.status === 'done' || i.status === 'instant' || i.status === 'error' || i.status === 'canceled'))

const statusLabel: Record<string, string> = {
  pending: '等待中',
  hashing: '计算中',
  uploading: '上传中',
  done: '完成',
  instant: '秒传',
  error: '失败',
  canceled: '已取消'
}

const formatProgress = (progress: number) => progress.toFixed(2)
</script>

<template>
  <Transition name="slide-up-queue">
    <div v-if="store.isOpen && store.queue.length > 0" class="upload-queue">
      <div class="upload-queue__header">
        <span class="upload-queue__title">
          {{ activeCount > 0 ? `上传中 (${activeCount})` : allDone ? '全部完成' : '上传队列' }}
        </span>
        <div style="display:flex;gap:4px">
          <OButton v-if="allDone" variant="subtle" size="sm" @click="store.clearDone">清除</OButton>
          <OButton variant="subtle" size="sm" :icon="true" @click="store.isOpen = false">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </OButton>
        </div>
      </div>
      <div class="upload-queue__list">
        <div v-for="item in store.queue" :key="item.id" class="upload-queue__item">
          <div class="upload-queue__name truncate">{{ item.fileName }}</div>
          <div class="upload-queue__info">
            <span class="upload-queue__status" :class="`upload-queue__status--${item.status}`">
              {{ statusLabel[item.status] }}
              <span v-if="item.chunkProgress" class="font-mono" style="margin-left:4px">{{ item.chunkProgress }}</span>
            </span>
            <span v-if="item.status === 'uploading' || item.status === 'hashing'" class="upload-queue__pct">{{ formatProgress(item.progress) }}%</span>
          </div>
          <div v-if="item.status === 'uploading' || item.status === 'hashing'" class="upload-queue__bar">
            <div class="upload-queue__bar-fill" :style="{ width: `${item.progress}%` }" />
          </div>
          <div v-if="item.status === 'uploading'" class="upload-queue__actions">
            <OButton variant="subtle" size="sm" @click="store.refreshUploadStatus(item.id)">刷新状态</OButton>
            <OButton variant="danger" size="sm" @click="store.cancelUpload(item.id)">取消</OButton>
          </div>
          <p v-if="item.error" class="upload-queue__error">{{ item.error }}</p>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.upload-queue {
  position: fixed; bottom: 20px; right: 20px; z-index: 150;
  width: 300px;
  background: var(--bg-overlay);
  border: 1px solid var(--border-hover);
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.5);
  overflow: hidden;
}
.upload-queue__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-elevated);
}
.upload-queue__title { font-size: 13px; font-weight: 500; }
.upload-queue__list { max-height: 260px; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 4px; }
.upload-queue__item { padding: 8px 10px; border-radius: 7px; background: var(--bg-elevated); }
.upload-queue__name { font-size: 12px; color: var(--text-primary); margin-bottom: 4px; }
.upload-queue__info { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.upload-queue__status { font-size: 11px; color: var(--text-secondary); }
.upload-queue__status--done,
.upload-queue__status--instant { color: var(--success); }
.upload-queue__status--canceled,
.upload-queue__status--error { color: var(--danger); }
.upload-queue__status--uploading,
.upload-queue__status--hashing { color: var(--accent); }
.upload-queue__pct { font-size: 11px; font-family: var(--font-mono); color: var(--text-secondary); }
.upload-queue__bar {
  height: 3px; background: var(--border); border-radius: 2px; overflow: hidden;
}
.upload-queue__bar-fill {
  height: 100%; background: var(--accent);
  border-radius: 2px;
  transition: width 200ms;
}
.upload-queue__error { font-size: 11px; color: var(--danger); margin-top: 3px; }
.upload-queue__actions { display: flex; gap: 6px; justify-content: flex-end; margin-top: 6px; }

.slide-up-queue-enter-active { transition: all 220ms var(--ease-out); }
.slide-up-queue-leave-active { transition: all 150ms; }
.slide-up-queue-enter-from { opacity: 0; transform: translateY(12px) scale(0.98); }
.slide-up-queue-leave-to   { opacity: 0; transform: translateY(6px); }
</style>
