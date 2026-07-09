<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import FileIcon from './FileIcon.vue'
import api from '@/api/client'
import type { FileEntity } from '@/types/file'

const props = defineProps<{ file: FileEntity; size?: number }>()

const objectUrl = ref<string | null>(null)
let currentObjectUrl: string | null = null

const sizePx = computed(() => props.size || 20)
const canLoadThumbnail = computed(() =>
  props.file.fileType === 'file' &&
  !!props.file.thumbnailPath &&
  !!props.file.mimeType?.startsWith('image/')
)

const thumbnailEndpoint = computed(() => {
  if (!canLoadThumbnail.value) return null
  if (props.file.teamId) return `/teams/${props.file.teamId}/files/${props.file.id}/thumbnail`
  return `/files/${props.file.id}/thumbnail`
})

function revokeObjectUrl() {
  if (currentObjectUrl) {
    URL.revokeObjectURL(currentObjectUrl)
    currentObjectUrl = null
  }
  objectUrl.value = null
}

async function loadThumbnail() {
  const endpoint = thumbnailEndpoint.value
  revokeObjectUrl()
  if (!endpoint) return

  try {
    const response = await api.get<Blob>(endpoint, {
      responseType: 'blob',
      suppressToast: true
    })
    currentObjectUrl = URL.createObjectURL(response.data)
    objectUrl.value = currentObjectUrl
  } catch {
    revokeObjectUrl()
  }
}

watch(
  () => [props.file.id, props.file.thumbnailPath, props.file.teamId],
  loadThumbnail,
  { immediate: true }
)

onBeforeUnmount(revokeObjectUrl)
</script>

<template>
  <span
    class="file-thumbnail"
    :style="{ width: `${sizePx}px`, height: `${sizePx}px` }"
  >
    <img
      v-if="objectUrl"
      :src="objectUrl"
      alt=""
      class="file-thumbnail__image"
      draggable="false"
    >
    <FileIcon
      v-else
      :mime-type="file.mimeType"
      :file-type="file.fileType"
      :size="sizePx"
    />
  </span>
</template>

<style scoped>
.file-thumbnail {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 6px;
}

.file-thumbnail__image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
