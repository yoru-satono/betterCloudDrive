<script setup lang="ts">
import FileCard from './FileCard.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import type { FileEntity } from '@/types/file'

defineProps<{
  files: FileEntity[]
  selectedIds: Set<number>
  loading: boolean
}>()
const emit = defineEmits<{
  'file-click': [FileEntity, MouseEvent]
  'file-dblclick': [FileEntity]
  'file-contextmenu': [FileEntity, MouseEvent]
}>()
</script>

<template>
  <div v-if="loading" class="file-grid__loading">
    <div v-for="n in 12" :key="n" class="file-grid__skeleton" />
  </div>
  <div v-else-if="files.length === 0" class="file-grid__empty">
    <OEmptyState title="此处没有文件" description="将文件拖拽到此处或点击上传">
      <template #icon>
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2">
          <path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/>
        </svg>
      </template>
    </OEmptyState>
  </div>
  <div v-else class="file-grid">
    <FileCard
      v-for="(file, idx) in files"
      :key="file.id"
      :file="file"
      :selected="selectedIds.has(file.id)"
      :index="idx"
      @click="(f: FileEntity, e: MouseEvent) => emit('file-click', f, e)"
      @dblclick="(f: FileEntity) => emit('file-dblclick', f)"
      @contextmenu="(f: FileEntity, e: MouseEvent) => emit('file-contextmenu', f, e)"
    />
  </div>
</template>

<style scoped>
.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 10px;
  padding: 4px 2px;
}
.file-grid__loading {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 10px;
  padding: 4px 2px;
}
.file-grid__skeleton {
  height: 100px;
  border-radius: 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  animation: pulse 1.4s ease-in-out infinite;
}
.file-grid__skeleton:nth-child(odd) { animation-delay: 0.1s; }
.file-grid__skeleton:nth-child(3n) { animation-delay: 0.25s; }
@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.7; }
}
</style>
