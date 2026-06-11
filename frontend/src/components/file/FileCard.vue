<script setup lang="ts">
import FileIcon from './FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import type { FileEntity } from '@/types/file'

const props = defineProps<{ file: FileEntity; selected: boolean; index?: number }>()
const emit = defineEmits<{
  click: [FileEntity, MouseEvent]
  dblclick: [FileEntity]
  contextmenu: [FileEntity, MouseEvent]
}>()

const { formatSize, formatDate } = useFormatters()
</script>

<template>
  <div
    class="file-card"
    :class="{ 'file-card--selected': selected }"
    :style="{ animationDelay: `${(index || 0) * 18}ms` }"
    @click="emit('click', file, $event)"
    @dblclick="emit('dblclick', file)"
    @contextmenu.prevent="emit('contextmenu', file, $event)"
  >
    <div class="file-card__icon">
      <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="28" />
    </div>
    <div class="file-card__name truncate">{{ file.fileName }}</div>
    <div class="file-card__meta">
      <span v-if="file.fileType !== 'folder'" class="font-mono">{{ formatSize(file.fileSize) }}</span>
      <span v-else class="text-muted">文件夹</span>
      <span class="text-muted">{{ formatDate(file.updatedAt) }}</span>
    </div>
    <div v-if="selected" class="file-card__check">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
        <polyline points="20 6 9 17 4 12" stroke="currentColor" stroke-width="3" fill="none" stroke-linecap="round"/>
      </svg>
    </div>
  </div>
</template>

<style scoped>
.file-card {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 14px 12px;
  cursor: pointer;
  position: relative;
  animation: slide-up 200ms var(--ease-out) both;
  transition: border-color var(--fast), background var(--fast), transform var(--fast), box-shadow var(--fast);
}
.file-card:hover {
  border-color: var(--border-hover);
  background: var(--bg-overlay);
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(0,0,0,0.3);
}
.file-card--selected {
  border-color: var(--accent) !important;
  background: var(--accent-dim) !important;
}
.file-card__icon { margin-bottom: 12px; }
.file-card__name { font-size: 13px; font-weight: 500; color: var(--text-primary); margin-bottom: 6px; }
.file-card__meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-secondary);
}
.file-card__check {
  position: absolute;
  top: 8px; right: 8px;
  width: 20px; height: 20px;
  background: var(--accent);
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: #080809;
}

@keyframes slide-up {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
