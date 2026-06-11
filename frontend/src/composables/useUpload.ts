import { ref } from 'vue'
import { useUploadStore } from '@/stores/upload'
import { useFilesStore } from '@/stores/files'

export function useUpload() {
  const uploadStore = useUploadStore()
  const filesStore = useFilesStore()
  const isDragging = ref(false)

  function onDragEnter(e: DragEvent) {
    if (e.dataTransfer?.types.includes('Files')) isDragging.value = true
  }

  function onDragLeave(e: DragEvent) {
    if (!(e.currentTarget as Element).contains(e.relatedTarget as Element)) {
      isDragging.value = false
    }
  }

  function onDragOver(e: DragEvent) {
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    isDragging.value = false
    const files = Array.from(e.dataTransfer?.files ?? [])
    if (files.length) uploadFiles(files)
  }

  function triggerFilePicker() {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    input.onchange = () => {
      if (input.files?.length) uploadFiles(Array.from(input.files))
    }
    input.click()
  }

  function uploadFiles(files: File[]) {
    uploadStore.addFiles(files, filesStore.currentParentId)
  }

  return { isDragging, onDragEnter, onDragLeave, onDragOver, onDrop, triggerFilePicker, uploadFiles }
}
