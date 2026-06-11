<script setup lang="ts">
import FileRow from './FileRow.vue'
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
  <div class="file-list">
    <div class="file-list__header">
      <div></div>
      <div></div>
      <div>名称</div>
      <div>大小</div>
      <div>修改时间</div>
    </div>
    <template v-if="loading">
      <div v-for="n in 8" :key="n" class="file-list__skeleton" />
    </template>
    <template v-else-if="files.length === 0">
      <OEmptyState title="此处没有文件" />
    </template>
    <template v-else>
      <FileRow
        v-for="file in files"
        :key="file.id"
        :file="file"
        :selected="selectedIds.has(file.id)"
        @click="(f: FileEntity, e: MouseEvent) => emit('file-click', f, e)"
        @dblclick="(f: FileEntity) => emit('file-dblclick', f)"
        @contextmenu="(f: FileEntity, e: MouseEvent) => emit('file-contextmenu', f, e)"
      />
    </template>
  </div>
</template>

<style scoped>
.file-list { padding: 4px 0; }
.file-list__header {
  display: grid;
  grid-template-columns: 32px 24px 1fr 90px 130px;
  gap: 8px;
  padding: 4px 12px 8px;
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
}
.file-list__skeleton {
  height: 38px; border-radius: 7px;
  background: var(--bg-elevated);
  margin-bottom: 2px;
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.7; }
}
</style>
