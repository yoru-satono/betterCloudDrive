import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { FileEntity } from '@/types/file'
import { previewFile } from '@/api/download'

export const usePreviewStore = defineStore('preview', () => {
  const open = ref(false)
  const target = ref<FileEntity | null>(null)
  const url = ref('')
  const error = ref('')
  const loading = ref(false)

  async function openPreview(file: FileEntity) {
    target.value = file
    open.value = true
    loading.value = true
    error.value = ''
    revokeUrl()
    try {
      const res = await previewFile(file.id)
      url.value = URL.createObjectURL(res.data)
    } catch {
      error.value = '此文件暂时无法预览'
    } finally {
      loading.value = false
    }
  }

  function closePreview() {
    open.value = false
    target.value = null
    error.value = ''
    loading.value = false
    revokeUrl()
  }

  function revokeUrl() {
    if (url.value) {
      URL.revokeObjectURL(url.value)
      url.value = ''
    }
  }

  return { open, target, url, error, loading, openPreview, closePreview }
})
