import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api/client'

export interface FileItem {
  id: number; fileName: string; fileType: 'file' | 'folder'
  mimeType?: string; fileSize: number; storagePath?: string
  md5Hash?: string; isDeleted: boolean; versionCount: number
  createdAt: string; updatedAt: string; parentId?: number
}

export const useFilesStore = defineStore('files', () => {
  const files = ref<FileItem[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(20)
  const pages = ref(0)
  const currentParentId = ref<number | null>(null)
  const breadcrumb = ref<Array<{ id: number | null; name: string }>>([{ id: null, name: '根目录' }])
  const loading = ref(false)
  const viewMode = ref<'list' | 'grid'>('list')
  const sortBy = ref('fileName')
  const order = ref('asc')

  async function fetchFiles(parentId?: number | null, p?: number) {
    loading.value = true
    try {
      const params: any = { page: p || page.value, size: size.value, sortBy: sortBy.value, order: order.value }
      if (parentId !== undefined) params.parentId = parentId ?? ''
      const { data } = await api.get('/files', { params })
      files.value = data.data.records
      total.value = data.data.total
      page.value = data.data.page
      pages.value = data.data.pages
      currentParentId.value = parentId ?? null
    } finally { loading.value = false }
  }

  async function searchFiles(q: string) {
    loading.value = true
    try {
      const { data } = await api.get('/files/search', { params: { q, page: page.value, size: size.value } })
      files.value = data.data.records
      total.value = data.data.total
    } finally { loading.value = false }
  }

  async function createFolder(parentId: number | null, folderName: string) {
    await api.post('/files/folder', { parentId, folderName })
    await fetchFiles(currentParentId.value)
  }

  async function renameFile(fileId: number, newName: string) {
    await api.put(`/files/${fileId}`, { newName })
    await fetchFiles(currentParentId.value)
  }

  async function deleteFiles(fileIds: number[]) {
    await api.delete('/files', { data: { fileIds } })
    await fetchFiles(currentParentId.value)
  }

  async function moveFile(fileId: number, targetParentId: number) {
    await api.post(`/files/${fileId}/move`, { targetParentId })
    await fetchFiles(currentParentId.value)
  }

  async function copyFile(fileId: number, targetParentId: number) {
    await api.post(`/files/${fileId}/copy`, { targetParentId })
    await fetchFiles(currentParentId.value)
  }

  function setViewMode(mode: 'list' | 'grid') { viewMode.value = mode }

  return { files, total, page, pages, currentParentId, breadcrumb, loading, viewMode, sortBy, order, fetchFiles, searchFiles, createFolder, renameFile, deleteFiles, moveFile, copyFile, setViewMode }
})
