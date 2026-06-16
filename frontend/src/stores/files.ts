import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as filesApi from '@/api/files'
import type { FileEntity, BreadcrumbItem } from '@/types/file'

export const useFilesStore = defineStore('files', () => {
  const files = ref<FileEntity[]>([])
  const total = ref(0)
  const page = ref(1)
  const pages = ref(0)
  const loading = ref(false)
  const searchResults = ref<FileEntity[]>([])
  const searchLoading = ref(false)
  const currentParentId = ref<number | null>(null)
  const breadcrumb = ref<BreadcrumbItem[]>([{ id: null, name: '全部文件' }])
  const viewMode = ref<'grid' | 'list'>('grid')
  const sortBy = ref('fileName')
  const order = ref('asc')
  const selectedIds = ref<Set<number>>(new Set())

  const selectedFiles = computed(() => files.value.filter(f => selectedIds.value.has(f.id)))
  const hasSelection = computed(() => selectedIds.value.size > 0)

  async function fetchFiles(parentId?: number | null, p = 1) {
    loading.value = true
    try {
      const params: filesApi.ListFilesParams = {
        page: p,
        size: 50,
        sortBy: sortBy.value,
        order: order.value
      }
      if (parentId !== undefined) {
        params.parentId = parentId === null ? undefined : parentId
      } else {
        params.parentId = currentParentId.value === null ? undefined : currentParentId.value
      }
      const { data } = await filesApi.listFiles(params)
      files.value = data.data.records
      total.value = data.data.total
      page.value = data.data.page
      pages.value = data.data.pages
      if (parentId !== undefined) currentParentId.value = parentId ?? null
    } finally {
      loading.value = false
    }
  }

  async function searchFiles(q: string) {
    searchLoading.value = true
    try {
      const { data } = await filesApi.searchFiles(q)
      searchResults.value = data.data.records
    } finally {
      searchLoading.value = false
    }
  }

  function clearSearchResults() {
    searchResults.value = []
  }

  async function refresh() {
    await fetchFiles(currentParentId.value)
  }

  function navigateTo(item: BreadcrumbItem) {
    const idx = breadcrumb.value.findIndex(b => b.id === item.id)
    if (idx >= 0) breadcrumb.value = breadcrumb.value.slice(0, idx + 1)
    else breadcrumb.value.push(item)
    fetchFiles(item.id)
  }

  async function openLocationFor(file: FileEntity) {
    await openDirectory(file.parentId ?? null)
  }

  async function openDirectory(folderId: number | null, folderHint?: FileEntity) {
    const nextBreadcrumb = folderId === null
      ? [{ id: null, name: '全部文件' }]
      : await buildBreadcrumb(folderId, folderHint)
    await fetchFiles(folderId)
    breadcrumb.value = nextBreadcrumb
    clearSelection()
  }

  async function buildBreadcrumb(folderId: number, folderHint?: FileEntity) {
    const items: BreadcrumbItem[] = []
    const visited = new Set<number>()
    let current: FileEntity | null = folderHint?.id === folderId ? folderHint : null
    let cursor: number | null = folderId

    while (cursor !== null && !visited.has(cursor)) {
      visited.add(cursor)
      if (!current || current.id !== cursor) {
        const { data } = await filesApi.getFile(cursor)
        current = data.data
      }
      items.unshift({ id: current.id, name: current.fileName })
      cursor = current.parentId
      current = null
    }

    return [{ id: null, name: '全部文件' }, ...items]
  }

  function enterFolder(folder: FileEntity) {
    return openDirectory(folder.id, folder)
  }

  function clearSelection() {
    selectedIds.value.clear()
  }

  function toggleSelect(id: number) {
    if (selectedIds.value.has(id)) selectedIds.value.delete(id)
    else selectedIds.value.add(id)
  }

  function selectAll() {
    files.value.forEach(f => selectedIds.value.add(f.id))
  }

  return {
    files, total, page, pages, loading, searchResults, searchLoading, currentParentId, breadcrumb,
    viewMode, sortBy, order, selectedIds, selectedFiles, hasSelection,
    fetchFiles, searchFiles, refresh, navigateTo, enterFolder,
    openDirectory, openLocationFor, clearSearchResults, clearSelection, toggleSelect, selectAll
  }
})
