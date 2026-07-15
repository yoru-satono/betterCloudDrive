<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FileToolbar from '@/components/file/FileToolbar.vue'
import FileBreadcrumb from '@/components/file/FileBreadcrumb.vue'
import FileGrid from '@/components/file/FileGrid.vue'
import FileList from '@/components/file/FileList.vue'
import FileContextMenu from '@/components/file/FileContextMenu.vue'
import UploadZone from '@/components/file/UploadZone.vue'
import FolderPickerModal from '@/components/file/FolderPickerModal.vue'
import OModal from '@/components/base/OModal.vue'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useFilesStore } from '@/stores/files'
import { useFileSelection } from '@/composables/useFileSelection'
import { useUpload } from '@/composables/useUpload'
import { useContextMenu } from '@/composables/useContextMenu'
import { useConfirm } from '@/composables/useConfirm'
import { useFormatters } from '@/composables/useFormatters'
import { usePreviewStore } from '@/stores/preview'
import * as filesApi from '@/api/files'
import * as favApi from '@/api/favorites'
import * as sharesApi from '@/api/shares'
import * as tagsApi from '@/api/tags'
import * as versionsApi from '@/api/versions'
import { downloadFile, downloadFolderZip } from '@/api/download'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'
import type { TagEntity } from '@/types/tag'
import type { FileVersionEntity } from '@/types/file'
import { buildShareUrl, isDesktopRuntime } from '@/config/runtime'
import { onFileAction, type FileActionDetail } from '@/components/file/fileActions'

const route = useRoute()
const router = useRouter()
const store = useFilesStore()
const selection = useFileSelection()
const upload = useUpload()
const ctx = useContextMenu()
const { confirm } = useConfirm()
const { formatSize, formatDateFull } = useFormatters()
const preview = usePreviewStore()
const fileInput = ref<HTMLInputElement | null>(null)
const desktopRuntime = isDesktopRuntime()

const showNewFolder = ref(false)
const newFolderName = ref('')
const showRename = ref(false)
const renameTarget = ref<FileEntity | null>(null)
const renameName = ref('')
const showShare = ref(false)
const shareTarget = ref<FileEntity | null>(null)
const shareMaxVisits = ref('')
const sharePasswordEnabled = ref(false)
const sharePasswordMode = ref<'manual' | 'generated'>('manual')
const sharePassword = ref('')
const shareNotifyEmail = ref('')
const generatedPasswordLength = ref<4 | 8>(4)
const pickerOpen = ref(false)
const pickerMode = ref<'move' | 'copy'>('move')
const pickerTarget = ref<FileEntity | null>(null)
const pickerLoading = ref(false)
const detailOpen = ref(false)
const detailTarget = ref<FileEntity | null>(null)
const tagOpen = ref(false)
const tagTarget = ref<FileEntity | null>(null)
const tags = ref<TagEntity[]>([])
const selectedTagIds = ref<Set<number>>(new Set())
const tagsLoading = ref(false)
const versionsOpen = ref(false)
const versionsTarget = ref<FileEntity | null>(null)
const versions = ref<FileVersionEntity[]>([])
const versionsLoading = ref(false)

const GENERATED_PASSWORD_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*_-+=?'

watch(
  () => route.params.folderId,
  (folderId) => {
    store.openDirectory(folderId ? Number(folderId) : null)
  },
  { immediate: true },
)

const stopFileAction = onFileAction(handleFileAction)
onUnmounted(stopFileAction)

function openContextMenu(file: FileEntity, event: MouseEvent) {
  ctx.open(event, buildMenu(file))
}

function buildMenu(file: FileEntity, options: { includeOpenLocation?: boolean; afterAction?: () => void } = {}) {
  const items = []
  const action = (run: () => void | Promise<void>) => () => {
    void run()
    options.afterAction?.()
  }
  if (options.includeOpenLocation) {
    items.push({ label: __t('file.searchoverlay.openFileLocation'), action: () => openLocation(file, options.afterAction) })
  }
  items.push({
    label: __t('commonUi.filepreview.download'),
    action: action(() => file.fileType === 'folder'
      ? downloadFolderZip(file.id, file.fileName)
      : downloadFile(file.id, file.fileName)),
  })
  if (file.fileType !== 'folder') {
    items.push({ label: isOfficeFile(file) ? __t('team.filebrowser.openDocument') : __t('team.filebrowser.preview'), action: action(() => preview.openPreview(file)) })
    items.push({ label: __t('team.filebrowser.versionManagement'), action: action(() => openVersions(file)) })
  }
  items.push({ label: __t('commonUi.adminlogs.details'), action: action(() => openDetail(file)) })
  items.push({ label: __t('file.filebrowser.manageTags'), action: action(() => openTagManager(file)) })
  items.push({ label: __t('team.filebrowser.moveTo'), action: action(() => openFolderAction(file, 'move')) })
  if (file.fileType !== 'folder') {
    items.push({ label: __t('file.filebrowser.copyTo'), action: action(() => openFolderAction(file, 'copy')) })
  }
  items.push({ label: __t('team.filebrowser.rename'), action: action(() => startRename(file)) })
  items.push({ label: __t('commonUi.searchoverlay.collect'), action: action(() => toggleFavorite(file)) })
  items.push({ label: __t('team.filebrowser.share'), action: action(() => openShareDialog(file)) })
  items.push({ divider: true, label: '', action: () => {} })
  items.push({ label: __t('commonUi.adminuserfiles.delete'), action: action(() => deleteSingle(file)), danger: true })
  return items
}

function handleFileAction(detail: FileActionDetail) {
  detail.handled = true
  if (detail.action === 'preview') {
    preview.openPreview(detail.file)
    return
  }
  if (detail.action === 'enter-folder') {
    router.push({ name: 'Folder', params: { folderId: detail.file.id } })
    return
  }
  if (detail.action === 'open-location') {
    openLocation(detail.file, detail.afterAction)
    return
  }
  if (detail.action === 'context-menu' && detail.event) {
    ctx.open(detail.event, buildMenu(detail.file, { includeOpenLocation: true, afterAction: detail.afterAction }))
  }
}

async function openLocation(file: FileEntity, afterAction?: () => void) {
  await router.push(file.parentId ? { name: 'Folder', params: { folderId: file.parentId } } : { name: 'Files' })
  afterAction?.()
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
  toast.success(__t('team.filebrowser.folderCreated'))
  newFolderName.value = ''
  showNewFolder.value = false
  store.refresh()
}

async function deleteSelected() {
  const ids = [...store.selectedIds]
  if (!ids.length) return
  const ok = await confirm(__t('team.filebrowser.confirmDeletion'), __t('file.filebrowser.movedFilesToTheTrashSoTheyCanBeRestored', { p0: ids.length }))
  if (!ok) return
  await filesApi.deleteFiles(ids)
  toast.success(__t('file.filebrowser.movedToTrash'))
  store.clearSelection()
  store.refresh()
}

async function deleteSingle(file: FileEntity) {
  const ok = await confirm(__t('team.filebrowser.confirmDeletion'), __t('team.filebrowser.moveToTheTrash', { p0: file.fileName }))
  if (!ok) return
  await filesApi.deleteFiles([file.id])
  toast.success(__t('file.filebrowser.movedToTrash'))
  store.refresh()
}

async function toggleFavorite(file: FileEntity) {
  await favApi.addFavorite(file.id)
  toast.success(__t('file.filebrowser.addedToFavorites'))
}

function openShareDialog(file: FileEntity) {
  shareTarget.value = file
  shareMaxVisits.value = ''
  sharePasswordEnabled.value = false
  sharePasswordMode.value = 'manual'
  sharePassword.value = ''
  shareNotifyEmail.value = ''
  generatedPasswordLength.value = 4
  showShare.value = true
}

function generateSharePassword(length: number) {
  if (length < 4 || length > 16) {
    throw new Error('Share password length must be between 4 and 16 characters')
  }
  const bytes = new Uint32Array(length)
  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * GENERATED_PASSWORD_CHARS.length)
    }
  }
  return Array.from(bytes, byte => GENERATED_PASSWORD_CHARS[byte % GENERATED_PASSWORD_CHARS.length]).join('')
}

async function submitShare() {
  if (!shareTarget.value) return
  const visits = shareMaxVisits.value.trim() ? Number(shareMaxVisits.value) : undefined
  if (visits !== undefined && (!Number.isInteger(visits) || visits < 1)) {
    toast.error(__t('commonUi.filebrowser.theNumberOfVisitsLimitMustBeAPositiveInteger'))
    return
  }
  let password: string | undefined
  if (sharePasswordEnabled.value) {
    password = sharePasswordMode.value === 'generated'
      ? generateSharePassword(generatedPasswordLength.value)
      : sharePassword.value.trim()
  }
  if (password !== undefined && (password.length < 4 || password.length > 16)) {
    toast.error(__t('commonUi.filebrowser.sharePasswordLengthMustBe416Characters'))
    return
  }
  const { data } = await sharesApi.createShare({
    fileId: shareTarget.value.id,
    maxVisits: visits,
    password,
    notifyEmail: shareNotifyEmail.value.trim() || undefined,
  })
  const shareUrl = buildShareUrl(data.data.shareCode)
  await navigator.clipboard.writeText(shareUrl).catch(() => {})
  showShare.value = false
  toast.success(__t('file.filebrowser.sharingLinkCopiedToClipboard'))
}

function openFolderAction(file: FileEntity, mode: 'move' | 'copy') {
  pickerTarget.value = file
  pickerMode.value = mode
  pickerOpen.value = true
}

async function confirmFolderAction(targetParentId: number | null) {
  if (!pickerTarget.value) return
  pickerLoading.value = true
  try {
    if (pickerMode.value === 'move') {
      await filesApi.moveFile(pickerTarget.value.id, targetParentId)
      toast.success(__t('team.filebrowser.moved'))
    } else {
      await filesApi.copyFile(pickerTarget.value.id, targetParentId)
      toast.success(__t('file.filebrowser.copied'))
    }
    pickerOpen.value = false
    pickerTarget.value = null
    store.refresh()
  } finally {
    pickerLoading.value = false
  }
}

async function openDetail(file: FileEntity) {
  const { data } = await filesApi.getFile(file.id)
  detailTarget.value = data.data
  detailOpen.value = true
}

async function openTagManager(file: FileEntity) {
  tagTarget.value = file
  tagOpen.value = true
  tagsLoading.value = true
  selectedTagIds.value = new Set()
  try {
    const { data } = await tagsApi.listTags()
    tags.value = data.data
    const selected = new Set<number>()
    await Promise.all(tags.value.map(async tag => {
      const filesRes = await tagsApi.listFilesByTag(tag.id, 1, 100)
      if (filesRes.data.data.records.some(item => item.id === file.id)) selected.add(tag.id)
    }))
    selectedTagIds.value = selected
  } finally {
    tagsLoading.value = false
  }
}

function toggleTag(tagId: number) {
  const next = new Set(selectedTagIds.value)
  if (next.has(tagId)) next.delete(tagId)
  else next.add(tagId)
  selectedTagIds.value = next
}

async function saveTags() {
  if (!tagTarget.value) return
  tagsLoading.value = true
  try {
    await Promise.all(tags.value.map(tag => {
      const selected = selectedTagIds.value.has(tag.id)
      return selected
        ? tagsApi.addFileTag(tagTarget.value!.id, tag.id)
        : tagsApi.removeFileTag(tagTarget.value!.id, tag.id)
    }))
    toast.success(__t('commonUi.filebrowser.labelUpdated'))
    tagOpen.value = false
  } finally {
    tagsLoading.value = false
  }
}

async function openVersions(file: FileEntity) {
  versionsTarget.value = file
  versionsOpen.value = true
  versionsLoading.value = true
  try {
    const { data } = await versionsApi.listVersions(file.id)
    versions.value = data.data
  } finally {
    versionsLoading.value = false
  }
}

async function deleteVersion(versionNumber: number) {
  if (!versionsTarget.value) return
  const ok = await confirm(__t('team.filebrowser.deleteVersion'), __t('team.filebrowser.confirmToDeleteVersion', { p0: versionNumber }))
  if (!ok) return
  await versionsApi.deleteVersion(versionsTarget.value.id, versionNumber)
  toast.success(__t('team.filebrowser.versionDeleted'))
  await openVersions(versionsTarget.value)
}

function handleDblclick(file: FileEntity) {
  if (file.fileType === 'folder') router.push({ name: 'Folder', params: { folderId: file.id } })
  else downloadFile(file.id, file.fileName)
}

function isOfficeFile(file: FileEntity) {
  const dot = file.fileName.lastIndexOf('.')
  if (dot < 0) return false
  return ['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'ods', 'csv', 'ppt', 'pptx', 'odp']
    .includes(file.fileName.slice(dot + 1).toLowerCase())
}

function triggerUpload() {
  if (desktopRuntime) {
    upload.triggerFilePicker()
    return
  }
  const input = fileInput.value
  if (!input) return
  const pickerInput = input as HTMLInputElement & { showPicker?: () => void }
  if (typeof pickerInput.showPicker === 'function') {
    try {
      pickerInput.showPicker()
      return
    } catch {
      // Fall back to click for browsers without a usable picker API.
    }
  }
  input.click()
}

function handleFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.length) upload.uploadFiles(Array.from(input.files))
  input.value = ''
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
      @upload="triggerUpload"
      @upload-folder="upload.triggerFolderPicker"
      @new-folder="showNewFolder = true"
      @delete="deleteSelected"
      @refresh="store.refresh"
    />
    <input
      ref="fileInput"
      class="file-browser__file-input"
      data-testid="file-upload-input"
      type="file"
      multiple
      aria-hidden="true"
      tabindex="-1"
      @change="handleFileInputChange"
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
    <OModal :title="__t('commonUi.filetoolbar.createNewFolder')" :open="showNewFolder" data-testid="new-folder-modal" @close="showNewFolder = false">
      <OInput v-model="newFolderName" :label="__t('team.filebrowser.folderName')" :placeholder="__t('team.filebrowser.enterName')" @keyup.enter="createFolder" />
      <template #footer>
        <OButton variant="ghost" @click="showNewFolder = false">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" @click="createFolder">{{ __t('commonUi.adminuserfiles.create') }}</OButton>
      </template>
    </OModal>

    <!-- Rename modal -->
    <OModal :title="__t('team.filebrowser.rename')" :open="showRename" data-testid="rename-modal" @close="showRename = false">
      <OInput v-model="renameName" :label="__t('team.filebrowser.newName')" :placeholder="__t('team.filebrowser.enterNewName')" @keyup.enter="submitRename" />
      <template #footer>
        <OButton variant="ghost" @click="showRename = false">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" @click="submitRename">{{ __t('commonUi.oconfirmdialog.confirm') }}</OButton>
      </template>
    </OModal>

    <OModal :title="__t('file.filebrowser.createShare')" :open="showShare" data-testid="share-modal" @close="showShare = false">
      <div class="share-form">
        <div class="share-form__name">{{ shareTarget?.fileName }}</div>
        <OInput
          v-model="shareMaxVisits"
          :label="__t('commonUi.filebrowser.visitLimit')"
          type="number"
          :placeholder="__t('commonUi.filebrowser.leaveBlankToIndicateNoLimit')"
          @keyup.enter="submitShare"
        />
        <OInput
          v-model="shareNotifyEmail"
          :label="__t('file.filebrowser.notificationEmail')"
          type="email"
          :placeholder="__t('file.filebrowser.leaveBlankToNotSendNotification')"
          @keyup.enter="submitShare"
        />
        <label class="share-password-toggle">
          <input v-model="sharePasswordEnabled" type="checkbox" />
          <span class="share-password-toggle__box">
            <span />
          </span>
          <span>{{ __t('file.filebrowser.setAccessPassword') }}</span>
        </label>
        <div v-if="sharePasswordEnabled" class="share-password">
          <div class="share-password__segments" :aria-label="__t('team.filebrowser.passwordSettingMethod')">
            <button
              type="button"
              :class="{ 'share-password__segment--active': sharePasswordMode === 'manual' }"
              @click="sharePasswordMode = 'manual'"
            >{{ __t('team.filebrowser.manualEntry') }}</button>
            <button
              type="button"
              :class="{ 'share-password__segment--active': sharePasswordMode === 'generated' }"
              @click="sharePasswordMode = 'generated'"
            >{{ __t('team.filebrowser.automaticallyGenerated') }}</button>
          </div>
          <OInput
            v-if="sharePasswordMode === 'manual'"
            v-model="sharePassword"
            :label="__t('commonUi.filebrowser.accessPassword')"
            type="text"
            :placeholder="__t('team.filebrowser.fourToSixteenBits')"
            @keyup.enter="submitShare"
          />
          <div v-else class="share-password__generated">
            <div class="share-password__label">{{ __t('team.filebrowser.passwordLength') }}</div>
            <div class="share-password__segments" :aria-label="__t('team.filebrowser.passwordLength')">
              <button
                type="button"
                :class="{ 'share-password__segment--active': generatedPasswordLength === 4 }"
                @click="generatedPasswordLength = 4"
              >{{ __t('team.filebrowser.fourDigits') }}</button>
              <button
                type="button"
                :class="{ 'share-password__segment--active': generatedPasswordLength === 8 }"
                @click="generatedPasswordLength = 8"
              >{{ __t('team.filebrowser.eightBits') }}</button>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="showShare = false">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" @click="submitShare">{{ __t('team.filebrowser.createAndCopyLink') }}</OButton>
      </template>
    </OModal>

    <FolderPickerModal
      :open="pickerOpen"
      :title="pickerMode === 'move' ? __t('team.filebrowser.moveTo') : __t('file.filebrowser.copyTo')"
      :target-name="pickerTarget?.fileName"
      :confirm-text="pickerMode === 'move' ? __t('team.filebrowser.moveHere') : __t('file.filebrowser.copyHere')"
      :loading="pickerLoading"
      :exclude-folder-id="pickerMode === 'move' && pickerTarget?.fileType === 'folder' ? pickerTarget.id : undefined"
      @close="pickerOpen = false"
      @confirm="confirmFolderAction"
    />

    <OModal :title="__t('commonUi.adminuserfiles.documentDetails')" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailTarget" class="info-list">
        <div><span>{{ __t('commonUi.filelist.name') }}</span><strong>{{ detailTarget.fileName }}</strong></div>
        <div><span>{{ __t('commonUi.adminlogs.type') }}</span><strong>{{ detailTarget.fileType === 'folder' ? __t('commonUi.desktopfolderupload.folder') : detailTarget.mimeType || __t('commonUi.searchoverlay.document') }}</strong></div>
        <div><span>{{ __t('commonUi.filelist.size') }}</span><strong class="font-mono">{{ detailTarget.fileType === 'folder' ? __t('commonUi.desktopfolderupload.folder') : formatSize(detailTarget.fileSize) }}</strong></div>
        <div><span>{{ __t('team.filebrowser.versionNumber') }}</span><strong class="font-mono">{{ detailTarget.versionCount }}</strong></div>
        <div><span>MD5</span><strong class="font-mono">{{ detailTarget.md5Hash || '-' }}</strong></div>
        <div><span>{{ __t('commonUi.adminteams.creationTime') }}</span><strong>{{ formatDateFull(detailTarget.createdAt) }}</strong></div>
        <div><span>{{ __t('commonUi.adminteams.updateTime') }}</span><strong>{{ formatDateFull(detailTarget.updatedAt) }}</strong></div>
      </div>
    </OModal>

    <OModal :title="__t('file.filebrowser.manageTags')" :open="tagOpen" @close="tagOpen = false">
      <div class="tag-manage">
        <div class="tag-manage__target">{{ tagTarget?.fileName }}</div>
        <div v-if="tagsLoading" class="tag-manage__loading"><OSpinner /></div>
        <div v-else-if="tags.length === 0" class="tag-manage__empty">{{ __t('file.filebrowser.thereIsNoLabelYetPleaseGoToLabelManagement') }}</div>
        <label v-else v-for="tag in tags" :key="tag.id" class="tag-check">
          <input type="checkbox" :checked="selectedTagIds.has(tag.id)" @change="toggleTag(tag.id)" />
          <span class="tag-dot" :style="{ background: tag.color || '#00d4aa' }" />
          <span>{{ tag.tagName }}</span>
        </label>
      </div>
      <template #footer>
        <OButton variant="ghost" :disabled="tagsLoading" @click="tagOpen = false">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" :loading="tagsLoading" @click="saveTags">{{ __t('commonUi.desktopsettings.save') }}</OButton>
      </template>
    </OModal>

    <OModal :title="__t('team.filebrowser.versionManagement')" :open="versionsOpen" @close="versionsOpen = false">
      <div class="versions-box">
        <div class="versions-box__target">{{ versionsTarget?.fileName }}</div>
        <div v-if="versionsLoading" class="versions-box__loading"><OSpinner /></div>
        <div v-else-if="versions.length === 0" class="versions-box__empty">{{ __t('commonUi.filepreview.noHistoricalVersionYet') }}</div>
        <div v-else class="versions-list">
          <div v-for="version in versions" :key="version.id" class="version-row">
            <div>
              <div class="version-row__title">{{ __t('commonUi.filepreview.version') }}{{ version.versionNumber }}</div>
              <div class="version-row__meta font-mono">{{ formatSize(version.fileSize) }} · {{ formatDateFull(version.createdAt) }}</div>
            </div>
            <OButton variant="danger" size="sm" @click="deleteVersion(version.versionNumber)">{{ __t('commonUi.adminuserfiles.delete') }}</OButton>
          </div>
        </div>
      </div>
    </OModal>
  </div>
</template>

<style scoped>
.file-browser { display: flex; flex-direction: column; gap: 12px; height: 100%; }
.file-browser__view { flex: 1; min-height: 0; overflow-y: auto; }
.file-browser__file-input {
  position: fixed;
  left: 0;
  bottom: 0;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
.share-form { display: flex; flex-direction: column; gap: 12px; }
.share-form__name { font-size: 13px; color: var(--text-secondary); overflow-wrap: anywhere; }
.share-password-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  width: fit-content;
}
.share-password-toggle input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.share-password-toggle__box {
  width: 30px;
  height: 17px;
  padding: 2px;
  border-radius: 999px;
  border: 1px solid var(--border-hover);
  background: var(--bg-surface);
  transition: all var(--fast);
}
.share-password-toggle__box span {
  display: block;
  width: 11px;
  height: 11px;
  border-radius: 999px;
  background: var(--text-muted);
  transition: all var(--fast);
}
.share-password-toggle input:checked + .share-password-toggle__box {
  border-color: var(--accent);
  background: var(--accent-dim);
}
.share-password-toggle input:checked + .share-password-toggle__box span {
  transform: translateX(13px);
  background: var(--accent);
}
.share-password {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
}
.share-password__label {
  color: var(--text-secondary);
  font-size: 13px;
}
.share-password__generated {
  display: flex;
  align-items: center;
  gap: 10px;
}
.share-password__segments {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 7px;
  padding: 2px;
  background: var(--bg-surface);
}
.share-password__segments button {
  min-width: 48px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 12px;
  padding: 4px 8px;
  cursor: pointer;
}
.share-password__segments button:hover { color: var(--text-primary); }
.share-password__segments .share-password__segment--active {
  background: var(--accent);
  color: var(--text-on-accent);
}
.info-list { display: flex; flex-direction: column; gap: 8px; }
.info-list div {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 10px;
  align-items: start;
  font-size: 13px;
}
.info-list span { color: var(--text-secondary); }
.info-list strong {
  color: var(--text-primary);
  font-weight: 500;
  overflow-wrap: anywhere;
}
.tag-manage__empty,
.tag-manage__loading,
.versions-box__empty,
.versions-box__loading {
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}
.tag-manage,
.versions-box { display: flex; flex-direction: column; gap: 10px; }
.tag-manage__target,
.versions-box__target {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.tag-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  font-size: 13px;
  cursor: pointer;
}
.tag-check input { accent-color: var(--accent); }
.tag-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.versions-list { display: flex; flex-direction: column; gap: 6px; }
.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.version-row__title { font-size: 13px; font-weight: 500; }
.version-row__meta { font-size: 11px; color: var(--text-secondary); margin-top: 2px; }
</style>
