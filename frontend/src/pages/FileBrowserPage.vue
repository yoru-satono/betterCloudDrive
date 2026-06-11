<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import FileToolbar from '@/components/file/FileToolbar.vue'
import FileBreadcrumb from '@/components/file/FileBreadcrumb.vue'
import FileGrid from '@/components/file/FileGrid.vue'
import FileList from '@/components/file/FileList.vue'
import FileContextMenu from '@/components/file/FileContextMenu.vue'
import UploadZone from '@/components/file/UploadZone.vue'
import OModal from '@/components/base/OModal.vue'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useFilesStore } from '@/stores/files'
import { useFileSelection } from '@/composables/useFileSelection'
import { useUpload } from '@/composables/useUpload'
import { useContextMenu } from '@/composables/useContextMenu'
import { useConfirm } from '@/composables/useConfirm'
import * as filesApi from '@/api/files'
import * as favApi from '@/api/favorites'
import * as sharesApi from '@/api/shares'
import { downloadFile } from '@/api/download'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'

const route = useRoute()
const store = useFilesStore()
const selection = useFileSelection()
const upload = useUpload()
const ctx = useContextMenu()
const { confirm } = useConfirm()

const showNewFolder = ref(false)
const newFolderName = ref('')
const showRename = ref(false)
const renameTarget = ref<FileEntity | null>(null)
const renameName = ref('')

onMounted(() => {
  const folderId = route.params.folderId ? Number(route.params.folderId) : null
  store.fetchFiles(folderId)
})

function openContextMenu(file: FileEntity, event: MouseEvent) {
  ctx.open(event, buildMenu(file))
}

function buildMenu(file: FileEntity) {
  const items = []
  if (file.fileType !== 'folder') {
    items.push({ label: '下载', action: () => downloadFile(file.id, file.fileName) })
  }
  items.push({ label: '重命名', action: () => startRename(file) })
  items.push({ label: '收藏', action: () => toggleFavorite(file) })
  items.push({ label: '分享', action: () => shareFile(file) })
  items.push({ divider: true, label: '', action: () => {} })
  items.push({ label: '删除', action: () => deleteSingle(file), danger: true })
  return items
}

function startRename(file: FileEntity) {
  renameTarget.value = file
  renameName.value = file.fileName
  showRename.value = true
}

async function submitRename() {
  if (!renameTarget.value || !renameName.value.trim()) return
  await filesApi.renameFile(renameTarget.value.id, renameName.value.trim())
  showRename.value = false
  store.refresh()
}

async function createFolder() {
  if (!newFolderName.value.trim()) return
  await filesApi.createFolder(store.currentParentId, newFolderName.value.trim())
  toast.success('文件夹已创建')
  newFolderName.value = ''
  showNewFolder.value = false
  store.refresh()
}

async function deleteSelected() {
  const ids = [...store.selectedIds]
  if (!ids.length) return
  const ok = await confirm('确认删除', `将 ${ids.length} 个文件移入回收站，可稍后恢复。`)
  if (!ok) return
  await filesApi.deleteFiles(ids)
  toast.success('已移入回收站')
  store.clearSelection()
  store.refresh()
}

async function deleteSingle(file: FileEntity) {
  const ok = await confirm('确认删除', `将「${file.fileName}」移入回收站？`)
  if (!ok) return
  await filesApi.deleteFiles([file.id])
  toast.success('已移入回收站')
  store.refresh()
}

async function toggleFavorite(file: FileEntity) {
  await favApi.addFavorite(file.id)
  toast.success('已添加到收藏')
}

async function shareFile(file: FileEntity) {
  const { data } = await sharesApi.createShare({ fileId: file.id })
  await navigator.clipboard.writeText(data.data.shareUrl).catch(() => {})
  toast.success('分享链接已复制到剪贴板')
}

function handleDblclick(file: FileEntity) {
  if (file.fileType === 'folder') store.enterFolder(file)
  else downloadFile(file.id, file.fileName)
}
</script>

<template>
  <div
    class="file-browser page-enter"
    @dragenter="upload.onDragEnter"
    @dragleave="upload.onDragLeave"
    @dragover="upload.onDragOver"
    @drop="upload.onDrop"
  >
    <FileToolbar
      @upload="upload.triggerFilePicker"
      @new-folder="showNewFolder = true"
      @delete="deleteSelected"
      @refresh="store.refresh"
    />
    <FileBreadcrumb />

    <div class="file-browser__view" @click.self="store.clearSelection">
      <FileGrid
        v-if="store.viewMode === 'grid'"
        :files="store.files"
        :selected-ids="store.selectedIds"
        :loading="store.loading"
        @file-click="(f, e) => selection.handleClick(f, e)"
        @file-dblclick="handleDblclick"
        @file-contextmenu="openContextMenu"
      />
      <FileList
        v-else
        :files="store.files"
        :selected-ids="store.selectedIds"
        :loading="store.loading"
        @file-click="(f, e) => selection.handleClick(f, e)"
        @file-dblclick="handleDblclick"
        @file-contextmenu="openContextMenu"
      />
    </div>

    <FileContextMenu
      :visible="ctx.visible.value"
      :x="ctx.x.value"
      :y="ctx.y.value"
      :items="ctx.items.value"
      @close="ctx.close"
    />

    <UploadZone :is-dragging="upload.isDragging.value" />

    <!-- New folder modal -->
    <OModal title="新建文件夹" :open="showNewFolder" @close="showNewFolder = false">
      <OInput v-model="newFolderName" label="文件夹名称" placeholder="输入名称" @keyup.enter="createFolder" />
      <template #footer>
        <OButton variant="ghost" @click="showNewFolder = false">取消</OButton>
        <OButton variant="primary" @click="createFolder">创建</OButton>
      </template>
    </OModal>

    <!-- Rename modal -->
    <OModal title="重命名" :open="showRename" @close="showRename = false">
      <OInput v-model="renameName" label="新名称" placeholder="输入新名称" @keyup.enter="submitRename" />
      <template #footer>
        <OButton variant="ghost" @click="showRename = false">取消</OButton>
        <OButton variant="primary" @click="submitRename">确认</OButton>
      </template>
    </OModal>
  </div>
</template>

<style scoped>
.file-browser { display: flex; flex-direction: column; gap: 12px; height: 100%; }
.file-browser__view { flex: 1; min-height: 0; overflow-y: auto; }
</style>
