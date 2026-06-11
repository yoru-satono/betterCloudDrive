<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import { useFilesStore } from '@/stores/files'

const emit = defineEmits<{
  upload: []
  'new-folder': []
  delete: []
  refresh: []
}>()
const store = useFilesStore()
</script>

<template>
  <div class="toolbar">
    <div class="toolbar__left">
      <OButton variant="primary" size="sm" @click="emit('upload')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
        </svg>
        上传文件
      </OButton>
      <OButton variant="ghost" size="sm" @click="emit('new-folder')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/>
        </svg>
        新建文件夹
      </OButton>
      <OButton
        v-if="store.hasSelection"
        variant="danger"
        size="sm"
        @click="emit('delete')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6"/>
        </svg>
        删除 ({{ store.selectedIds.size }})
      </OButton>
    </div>
    <div class="toolbar__right">
      <span v-if="store.hasSelection" class="toolbar__sel-info">已选 {{ store.selectedIds.size }} 项</span>
      <OButton variant="subtle" size="sm" :icon="true" @click="emit('refresh')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="23 4 23 10 17 10"/><path d="M20.5 16a9 9 0 11-2.5-8.5L23 10"/>
        </svg>
      </OButton>
      <div class="toolbar__view-toggle">
        <button
          class="toolbar__view-btn"
          :class="{ 'toolbar__view-btn--active': store.viewMode === 'grid' }"
          @click="store.viewMode = 'grid'"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>
          </svg>
        </button>
        <button
          class="toolbar__view-btn"
          :class="{ 'toolbar__view-btn--active': store.viewMode === 'list' }"
          @click="store.viewMode = 'list'"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/>
            <line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 0; flex-wrap: wrap;
}
.toolbar__left, .toolbar__right { display: flex; align-items: center; gap: 6px; }
.toolbar__sel-info { font-size: 12px; color: var(--accent); font-weight: 500; }
.toolbar__view-toggle {
  display: flex; background: var(--bg-elevated);
  border: 1px solid var(--border); border-radius: 7px; overflow: hidden;
}
.toolbar__view-btn {
  background: transparent; border: none; cursor: pointer;
  color: var(--text-muted); padding: 6px 8px;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--fast);
}
.toolbar__view-btn:hover { color: var(--text-secondary); background: var(--bg-overlay); }
.toolbar__view-btn--active { color: var(--accent); background: var(--accent-dim); }
</style>
