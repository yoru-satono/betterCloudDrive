import { computed } from 'vue'
import type { FileEntity } from '@/types/file'
import { useFilesStore } from '@/stores/files'

export function useFileSelection() {
  const store = useFilesStore()

  function handleClick(file: FileEntity, event: MouseEvent) {
    if (event.metaKey || event.ctrlKey) {
      store.toggleSelect(file.id)
      return
    }
    if (event.shiftKey && store.selectedIds.size > 0) {
      const ids = store.files.map(f => f.id)
      const lastSelected = [...store.selectedIds].pop()!
      const from = ids.indexOf(lastSelected)
      const to = ids.indexOf(file.id)
      const [start, end] = from < to ? [from, to] : [to, from]
      ids.slice(start, end + 1).forEach(id => store.selectedIds.add(id))
      return
    }
    store.clearSelection()
    store.toggleSelect(file.id)
  }

  const isSelected = (id: number) => store.selectedIds.has(id)

  return {
    selectedIds: computed(() => store.selectedIds),
    selectedFiles: store.selectedFiles,
    hasSelection: computed(() => store.hasSelection),
    handleClick,
    isSelected,
    clearSelection: store.clearSelection,
    selectAll: store.selectAll
  }
}
