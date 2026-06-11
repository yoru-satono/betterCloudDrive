<script setup lang="ts">
import FileIcon from './FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import type { FileEntity } from '@/types/file'

defineProps<{ file: FileEntity; selected: boolean }>()
const emit = defineEmits<{
  click: [FileEntity, MouseEvent]
  dblclick: [FileEntity]
  contextmenu: [FileEntity, MouseEvent]
}>()

const { formatSize, formatDate } = useFormatters()
</script>

<template>
  <div
    class="file-row"
    :class="{ 'file-row--selected': selected }"
    @click="emit('click', file, $event)"
    @dblclick="emit('dblclick', file)"
    @contextmenu.prevent="emit('contextmenu', file, $event)"
  >
    <div class="file-row__check">
      <div class="file-row__checkbox" :class="{ 'file-row__checkbox--checked': selected }">
        <svg v-if="selected" width="10" height="10" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3" fill="none" stroke-linecap="round">
          <polyline points="20 6 9 17 4 12"/>
        </svg>
      </div>
    </div>
    <div class="file-row__icon">
      <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="16" />
    </div>
    <div class="file-row__name truncate">{{ file.fileName }}</div>
    <div class="file-row__size font-mono">
      {{ file.fileType === 'folder' ? '—' : formatSize(file.fileSize) }}
    </div>
    <div class="file-row__date">{{ formatDate(file.updatedAt) }}</div>
  </div>
</template>

<style scoped>
.file-row {
  display: grid;
  grid-template-columns: 32px 24px 1fr 90px 130px;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 7px;
  cursor: pointer;
  transition: background var(--fast);
  border: 1px solid transparent;
}
.file-row:hover { background: var(--bg-elevated); }
.file-row--selected {
  background: var(--accent-dim) !important;
  border-color: rgba(0, 212, 170, 0.15);
}
.file-row__check { display: flex; align-items: center; justify-content: center; }
.file-row__checkbox {
  width: 16px; height: 16px;
  border: 1px solid var(--border-hover);
  border-radius: 4px;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--fast);
}
.file-row__checkbox--checked {
  background: var(--accent);
  border-color: var(--accent);
  color: #080809;
}
.file-row__name { font-size: 13px; color: var(--text-primary); }
.file-row__size { font-size: 11px; color: var(--text-secondary); text-align: right; }
.file-row__date { font-size: 11px; color: var(--text-secondary); text-align: right; }
</style>
