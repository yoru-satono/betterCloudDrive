<script setup lang="ts">
import { ref, watch } from 'vue'
import { useUIStore } from '@/stores/ui'
import { useFilesStore } from '@/stores/files'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import { onKeyStroke } from '@vueuse/core'

const ui = useUIStore()
const files = useFilesStore()
const { formatSize } = useFormatters()
const q = ref('')

onKeyStroke('k', (e) => {
  if (e.metaKey || e.ctrlKey) { e.preventDefault(); ui.openSearch() }
})
onKeyStroke('Escape', () => { if (ui.searchOpen) ui.closeSearch() })

watch(q, async (val) => {
  if (val.trim().length >= 1) await files.searchFiles(val.trim())
})

function handleClose() {
  ui.closeSearch()
  q.value = ''
}
</script>

<template>
  <Teleport to="body">
    <Transition name="search-overlay">
      <div v-if="ui.searchOpen" class="search-bg" @click.self="handleClose">
        <div class="search-panel">
          <div class="search-input-row">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="2">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              v-model="q"
              class="search-input"
              placeholder="搜索文件名..."
              autofocus
            />
            <kbd @click="handleClose">Esc</kbd>
          </div>
          <div v-if="q.length > 0" class="search-results">
            <div v-if="files.loading" class="search-loading">搜索中...</div>
            <div
              v-else-if="files.files.length === 0"
              class="search-empty"
            >未找到相关文件</div>
            <div
              v-else
              v-for="file in files.files.slice(0, 10)"
              :key="file.id"
              class="search-item"
              @click="handleClose"
            >
              <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="16" />
              <span class="search-item__name truncate">{{ file.fileName }}</span>
              <span class="search-item__size font-mono">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }}</span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.search-bg {
  position: fixed; inset: 0; z-index: 500;
  background: rgba(0,0,0,0.65);
  backdrop-filter: blur(6px);
  display: flex; align-items: flex-start; justify-content: center;
  padding-top: 15vh;
}
.search-panel {
  width: 560px; max-width: 95vw;
  background: var(--bg-overlay);
  border: 1px solid var(--border-hover);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 24px 80px rgba(0,0,0,0.6);
}
.search-input-row {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}
.search-input {
  flex: 1; background: transparent; border: none; outline: none;
  color: var(--text-primary); font-family: var(--font-sans); font-size: 15px;
}
.search-input::placeholder { color: var(--text-muted); }
kbd {
  font-size: 11px; font-family: var(--font-mono); cursor: pointer;
  background: var(--bg-elevated); border: 1px solid var(--border-hover);
  border-radius: 4px; padding: 2px 6px; color: var(--text-muted);
}
.search-results { padding: 6px; max-height: 360px; overflow-y: auto; }
.search-loading, .search-empty { padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px; }
.search-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 7px; cursor: pointer;
  transition: background var(--fast);
}
.search-item:hover { background: var(--bg-elevated); }
.search-item__name { flex: 1; font-size: 13px; }
.search-item__size { font-size: 11px; color: var(--text-secondary); flex-shrink: 0; }

.search-overlay-enter-active { transition: all 160ms var(--ease-out); }
.search-overlay-leave-active { transition: all 100ms; }
.search-overlay-enter-from { opacity: 0; }
.search-overlay-leave-to   { opacity: 0; }
.search-overlay-enter-from .search-panel { transform: scale(0.97) translateY(-8px); }
.search-panel { transition: transform 160ms var(--ease-out); }
</style>
