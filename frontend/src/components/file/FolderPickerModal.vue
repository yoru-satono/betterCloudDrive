<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import * as filesApi from '@/api/files'
import type { FileEntity } from '@/types/file'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  targetName?: string
  confirmText?: string
  loading?: boolean
  excludeFolderId?: number
}>(), {
  title: '选择文件夹',
  confirmText: '选择此处',
})

const emit = defineEmits<{
  close: []
  confirm: [parentId: number | null]
}>()

const foldersLoading = ref(false)
const folders = ref<FileEntity[]>([])
const error = ref('')
const currentParentId = ref<number | null>(null)
const folderStack = ref<Array<{ id: number | null; name: string }>>([{ id: null, name: '我的网盘' }])
const currentFolder = computed(() => folderStack.value[folderStack.value.length - 1])

watch(() => props.open, async open => {
  if (open) {
    folderStack.value = [{ id: null, name: '我的网盘' }]
    await loadFolders(null)
  }
})

async function loadFolders(parentId: number | null) {
  foldersLoading.value = true
  error.value = ''
  currentParentId.value = parentId
  try {
    const { data } = await filesApi.listFiles({ parentId, page: 1, size: 100 })
    folders.value = data.data.records.filter(item => item.fileType === 'folder' && item.id !== props.excludeFolderId)
  } catch {
    error.value = '文件夹加载失败'
  } finally {
    foldersLoading.value = false
  }
}

async function enterFolder(folder: FileEntity) {
  folderStack.value.push({ id: folder.id, name: folder.fileName })
  await loadFolders(folder.id)
}

async function goBackFolder() {
  if (folderStack.value.length <= 1) return
  folderStack.value.pop()
  await loadFolders(currentFolder.value.id)
}
</script>

<template>
  <OModal :open="open" :title="title" width="460px" @close="emit('close')">
    <div class="folder-picker">
      <div v-if="targetName" class="folder-picker__target">{{ targetName }}</div>
      <div class="folder-picker__nav">
        <OButton variant="ghost" size="sm" :disabled="folderStack.length <= 1 || foldersLoading" @click="goBackFolder">
          返回
        </OButton>
        <span class="folder-picker__path">{{ currentFolder.name }}</span>
      </div>
      <div v-if="foldersLoading" class="folder-picker__loading"><OSpinner /></div>
      <div v-else-if="error" class="folder-picker__error">{{ error }}</div>
      <div v-else-if="folders.length === 0" class="folder-picker__empty">当前目录没有文件夹</div>
      <div v-else class="folder-picker__list">
        <button
          v-for="folder in folders"
          :key="folder.id"
          class="folder-picker__item"
          @click="enterFolder(folder)"
        >
          <FileIcon :mime-type="folder.mimeType" :file-type="folder.fileType" :size="16" />
          <span>{{ folder.fileName }}</span>
        </button>
      </div>
    </div>
    <template #footer>
      <OButton variant="ghost" :disabled="loading" @click="emit('close')">取消</OButton>
      <OButton variant="primary" :loading="loading" :disabled="foldersLoading" @click="emit('confirm', currentParentId)">
        {{ confirmText }}
      </OButton>
    </template>
  </OModal>
</template>

<style scoped>
.folder-picker { display: flex; flex-direction: column; gap: 10px; }
.folder-picker__target {
  font-size: 13px;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.folder-picker__nav { display: flex; align-items: center; gap: 10px; }
.folder-picker__path { font-size: 13px; color: var(--text-primary); }
.folder-picker__loading,
.folder-picker__error,
.folder-picker__empty {
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}
.folder-picker__error { color: var(--danger); }
.folder-picker__list {
  max-height: 260px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.folder-picker__item {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  cursor: pointer;
  text-align: left;
}
.folder-picker__item:hover {
  border-color: var(--border-hover);
  background: var(--bg-overlay);
}
</style>
