<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileGrid from '@/components/file/FileGrid.vue'
import FileList from '@/components/file/FileList.vue'
import FileContextMenu from '@/components/file/FileContextMenu.vue'
import FolderPickerModal from '@/components/file/FolderPickerModal.vue'
import UploadZone from '@/components/file/UploadZone.vue'
import { useTeamFilesStore } from '@/stores/teamFiles'
import { useTeamsStore } from '@/stores/teams'
import { useUploadStore } from '@/stores/upload'
import { useContextMenu } from '@/composables/useContextMenu'
import type { ContextMenuItem } from '@/composables/useContextMenu'
import { useConfirm } from '@/composables/useConfirm'
import { usePreviewStore } from '@/stores/preview'
import { useFormatters } from '@/composables/useFormatters'
import * as teamsApi from '@/api/teams'
import * as versionsApi from '@/api/versions'
import { downloadTeamFile, downloadTeamFolderZip } from '@/api/download'
import { buildShareUrl, isDesktopRuntime } from '@/config/runtime'
import {
  registerDesktopUploadListeners,
  uploadDesktopFiles,
  uploadDesktopFolder,
} from '@/api/desktopUpload'
import { toast } from 'vue-sonner'
import type { FileEntity, FileVersionEntity } from '@/types/file'
import type { TagEntity } from '@/types/tag'
import type { TeamEffectivePermission, TeamFilePermission, TeamMember, TeamRole } from '@/types/team'

const route = useRoute()
const router = useRouter()
const store = useTeamFilesStore()
const teamsStore = useTeamsStore()
const uploadStore = useUploadStore()
const ctx = useContextMenu()
const { confirm } = useConfirm()
const preview = usePreviewStore()
const { formatSize, formatDateFull } = useFormatters()
const desktopRuntime = isDesktopRuntime()

const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)
const showNewFolder = ref(false)
const newFolderName = ref('')
const showRename = ref(false)
const renameTarget = ref<FileEntity | null>(null)
const renameName = ref('')
const pickerOpen = ref(false)
const pickerTarget = ref<FileEntity | null>(null)
const pickerLoading = ref(false)
const detailOpen = ref(false)
const detailTarget = ref<FileEntity | null>(null)
const membersOpen = ref(false)
const membersLoading = ref(false)
const members = ref<TeamMember[]>([])
const addUsername = ref('')
const addRole = ref<Exclude<TeamRole, 'OWNER'>>('VIEWER')
const searchQuery = ref('')
const versionsOpen = ref(false)
const versionsTarget = ref<FileEntity | null>(null)
const versions = ref<FileVersionEntity[]>([])
const versionsLoading = ref(false)
const showShare = ref(false)
const shareTarget = ref<FileEntity | null>(null)
const shareMaxVisits = ref('')
const sharePasswordEnabled = ref(false)
const sharePasswordMode = ref<'manual' | 'generated'>('manual')
const sharePassword = ref('')
const shareNotifyEmail = ref('')
const generatedPasswordLength = ref<4 | 8>(4)
const tagOpen = ref(false)
const tagTarget = ref<FileEntity | null>(null)
const teamTags = ref<TagEntity[]>([])
const tagSelection = ref<Record<number, boolean>>({})
const originalTagIds = ref<Set<number>>(new Set())
const tagsLoading = ref(false)
const permissionOpen = ref(false)
const permissionTarget = ref<FileEntity | null>(null)
const permissions = ref<TeamFilePermission[]>([])
const permissionEffective = ref<TeamEffectivePermission | null>(null)
const permissionsLoading = ref(false)
const permissionSubjectType = ref<'ROLE' | 'USER'>('ROLE')
const permissionSubjectRole = ref<Exclude<TeamRole, 'OWNER'>>('VIEWER')
const permissionSubjectUserId = ref('')
const permissionForm = ref<TeamEffectivePermission>({ canRead: true, canWrite: false, canShare: false, canDelete: false, canManage: false })

const GENERATED_PASSWORD_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*_-+=?'

const teamId = computed(() => Number(route.params.teamId))
const folderId = computed(() => route.params.folderId ? Number(route.params.folderId) : null)

watch(
  () => [route.params.teamId, route.params.folderId],
  async () => {
    if (!Number.isFinite(teamId.value)) return
    await store.openDirectory(teamId.value, folderId.value)
  },
  { immediate: true },
)

let disposeDesktopUpload: (() => void) | undefined
onMounted(async () => {
  if (!teamsStore.teams.length) {
    await teamsStore.fetchTeams().catch(() => {})
  }
  if (desktopRuntime) {
    disposeDesktopUpload = await registerDesktopUploadListeners({
      onItemUpdated: item => uploadStore.applyDesktopUploadItem(item),
      onBatchCompleted: () => {
        void store.refresh()
        toast.success('团队文件上传完成')
      },
      onError: message => toast.error(message),
    })
  }
})

onUnmounted(() => {
  disposeDesktopUpload?.()
})

function navigateTo(item: { id: number | null }) {
  router.push(item.id === null
    ? { name: 'TeamFiles', params: { teamId: teamId.value } }
    : { name: 'TeamFolder', params: { teamId: teamId.value, folderId: item.id } })
}

function onDragEnter(e: DragEvent) {
  if (!store.canWrite) return
  if (e.dataTransfer?.types.includes('Files')) isDragging.value = true
}

function onDragLeave(e: DragEvent) {
  if (!(e.currentTarget as Element).contains(e.relatedTarget as Element)) {
    isDragging.value = false
  }
}

function onDragOver(e: DragEvent) {
  if (!store.canWrite) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDrop(e: DragEvent) {
  if (!store.canWrite) return
  e.preventDefault()
  isDragging.value = false
  const files = Array.from(e.dataTransfer?.files ?? [])
  if (files.length) uploadStore.addFiles(files, store.currentParentId, teamId.value)
}

function triggerUpload() {
  if (!store.canWrite) return
  if (desktopRuntime) {
    uploadDesktopFiles(store.currentParentId, teamId.value)
      .then(result => {
        if (result.selected && result.rootName) toast.success(`${result.rootName} 已加入传输队列`)
      })
      .catch(error => toast.error(error instanceof Error ? error.message : '上传文件失败'))
    return
  }
  fileInput.value?.click()
}

function triggerFolderUpload() {
  if (!store.canWrite) return
  uploadDesktopFolder(store.currentParentId, teamId.value)
    .then(result => {
      if (result.selected && result.rootName) toast.success(`${result.rootName} 已加入传输队列`)
    })
    .catch(error => toast.error(error instanceof Error ? error.message : '上传文件夹失败'))
}

function handleFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.length) uploadStore.addFiles(Array.from(input.files), store.currentParentId, teamId.value)
  input.value = ''
}

function openContextMenu(file: FileEntity, event: MouseEvent) {
  const items: ContextMenuItem[] = [
    {
      label: '下载',
      action: () => file.fileType === 'folder'
        ? downloadTeamFolderZip(teamId.value, file.id, file.fileName)
        : downloadTeamFile(teamId.value, file.id, file.fileName),
    },
  ]
  if (file.fileType !== 'folder') {
    items.push({ label: isOfficeFile(file) ? '打开文档' : '预览', action: () => preview.openPreview(file, { teamId: teamId.value }) })
    items.push({ label: '版本管理', action: () => openVersions(file) })
  }
  if (store.canShare) {
    items.push({ label: '分享', action: () => openShareDialog(file) })
  }
  items.push({ label: '收藏', action: () => favoriteFile(file) })
  if (store.canWrite) {
    items.push({ label: '标签', action: () => openTags(file) })
  }
  items.push({ label: '详情', action: () => openDetail(file) })
  if (store.canWrite) {
    items.push({ label: '移动到', action: () => openMove(file) })
    items.push({ label: '重命名', action: () => startRename(file) })
  }
  if (store.canManageFiles) {
    items.push({ label: '权限', action: () => openPermissions(file) })
  }
  if (store.canDelete) {
    items.push({ divider: true, label: '', action: () => {} })
    items.push({ label: '删除', action: () => deleteSingle(file), danger: true })
  }
  ctx.open(event, items)
}

function handleDblclick(file: FileEntity) {
  if (file.fileType === 'folder') {
    router.push({ name: 'TeamFolder', params: { teamId: teamId.value, folderId: file.id } })
    return
  }
  downloadTeamFile(teamId.value, file.id, file.fileName)
}

async function createFolder() {
  if (!store.canWrite || !newFolderName.value.trim()) return
  await teamsApi.createTeamFolder(teamId.value, store.currentParentId, newFolderName.value.trim())
  toast.success('文件夹已创建')
  newFolderName.value = ''
  showNewFolder.value = false
  await store.refresh()
}

function startRename(file: FileEntity) {
  renameTarget.value = file
  renameName.value = file.fileName
  showRename.value = true
}

async function submitRename() {
  if (!store.canWrite || !renameTarget.value || !renameName.value.trim()) return
  await teamsApi.renameTeamFile(teamId.value, renameTarget.value.id, renameName.value.trim())
  showRename.value = false
  await store.refresh()
}

async function deleteSelected() {
  if (!store.canDelete) return
  const ids = [...store.selectedIds]
  if (!ids.length) return
  const ok = await confirm('确认删除', `将 ${ids.length} 个团队文件移入回收站。`)
  if (!ok) return
  await teamsApi.deleteTeamFiles(teamId.value, ids)
  toast.success('已删除')
  store.clearSelection()
  await store.refresh()
}

async function deleteSingle(file: FileEntity) {
  if (!store.canDelete) return
  const ok = await confirm('确认删除', `将「${file.fileName}」移入回收站？`)
  if (!ok) return
  await teamsApi.deleteTeamFiles(teamId.value, [file.id])
  toast.success('已删除')
  await store.refresh()
}

function openMove(file: FileEntity) {
  pickerTarget.value = file
  pickerOpen.value = true
}

async function confirmMove(targetParentId: number | null) {
  if (!pickerTarget.value) return
  pickerLoading.value = true
  try {
    await teamsApi.moveTeamFile(teamId.value, pickerTarget.value.id, targetParentId)
    toast.success('已移动')
    pickerOpen.value = false
    pickerTarget.value = null
    await store.refresh()
  } finally {
    pickerLoading.value = false
  }
}

async function openDetail(file: FileEntity) {
  const { data } = await teamsApi.getTeamFile(teamId.value, file.id)
  detailTarget.value = data.data
  detailOpen.value = true
}

async function favoriteFile(file: FileEntity) {
  await teamsApi.addTeamFavorite(teamId.value, file.id)
  toast.success('已加入收藏')
}

async function openTags(file: FileEntity) {
  tagTarget.value = file
  tagOpen.value = true
  tagsLoading.value = true
  try {
    const { data } = await teamsApi.listTeamTags(teamId.value)
    teamTags.value = data.data
    const selected = new Set<number>()
    await Promise.all(teamTags.value.map(async tag => {
      const filesRes = await teamsApi.listTeamFilesByTag(teamId.value, tag.id, 1, 100)
      if (filesRes.data.data.records.some(item => item.id === file.id)) selected.add(tag.id)
    }))
    originalTagIds.value = selected
    tagSelection.value = Object.fromEntries(teamTags.value.map(tag => [tag.id, selected.has(tag.id)]))
  } finally {
    tagsLoading.value = false
  }
}

async function saveTags() {
  if (!tagTarget.value) return
  tagsLoading.value = true
  try {
    await Promise.all(teamTags.value.map(tag => {
      const selected = !!tagSelection.value[tag.id]
      const wasSelected = originalTagIds.value.has(tag.id)
      if (selected === wasSelected) return Promise.resolve()
      return selected
        ? teamsApi.addTeamFileTag(teamId.value, tagTarget.value!.id, tag.id)
        : teamsApi.removeTeamFileTag(teamId.value, tagTarget.value!.id, tag.id)
    }))
    toast.success('标签已更新')
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
    const { data } = await versionsApi.listTeamVersions(teamId.value, file.id)
    versions.value = data.data
  } finally {
    versionsLoading.value = false
  }
}

async function deleteVersion(versionNumber: number) {
  if (!store.canWrite || !versionsTarget.value) return
  const ok = await confirm('删除版本', `确认删除版本 ${versionNumber}？`)
  if (!ok) return
  await versionsApi.deleteTeamVersion(teamId.value, versionsTarget.value.id, versionNumber)
  toast.success('版本已删除')
  await openVersions(versionsTarget.value)
}

async function downloadVersion(versionNumber: number) {
  if (!versionsTarget.value) return
  await versionsApi.downloadTeamVersion(teamId.value, versionsTarget.value.id, versionNumber, versionsTarget.value.fileName)
}

async function restoreVersion(versionNumber: number) {
  if (!store.canWrite || !versionsTarget.value) return
  const ok = await confirm('恢复版本', `将版本 ${versionNumber} 恢复为当前文件？当前文件会保留为历史版本。`)
  if (!ok) return
  await versionsApi.restoreTeamVersion(teamId.value, versionsTarget.value.id, versionNumber)
  toast.success('版本已恢复')
  await openVersions(versionsTarget.value)
  await store.refresh()
}

function openShareDialog(file: FileEntity) {
  if (!store.canShare) return
  shareTarget.value = file
  shareMaxVisits.value = ''
  sharePasswordEnabled.value = false
  sharePasswordMode.value = 'manual'
  sharePassword.value = ''
  shareNotifyEmail.value = ''
  generatedPasswordLength.value = 4
  showShare.value = true
}

async function openPermissions(file: FileEntity) {
  permissionTarget.value = file
  permissionOpen.value = true
  permissionEffective.value = null
  await loadPermissions()
}

async function loadPermissions() {
  if (!permissionTarget.value) return
  permissionsLoading.value = true
  try {
    const [permissionsResult, effectiveResult] = await Promise.all([
      teamsApi.listTeamFilePermissions(teamId.value, permissionTarget.value.id),
      teamsApi.getTeamFileEffectivePermission(teamId.value, permissionTarget.value.id),
    ])
    permissions.value = permissionsResult.data.data
    permissionEffective.value = effectiveResult.data.data
  } finally {
    permissionsLoading.value = false
  }
}

async function savePermission() {
  if (!permissionTarget.value) return
  let subjectUserId: number | null = null
  if (permissionSubjectType.value === 'USER') {
    const parsedUserId = Number(permissionSubjectUserId.value)
    if (!Number.isInteger(parsedUserId) || parsedUserId <= 0) {
      toast.error('请输入有效的用户 ID')
      return
    }
    subjectUserId = parsedUserId
  }
  if (permissionForm.value.canManage && !permissionForm.value.canRead) {
    toast.error('管理权限必须同时保留读取权限')
    return
  }
  await teamsApi.upsertTeamFilePermission(teamId.value, permissionTarget.value.id, {
    subjectType: permissionSubjectType.value,
    subjectUserId,
    subjectRole: permissionSubjectType.value === 'ROLE' ? permissionSubjectRole.value : null,
    ...permissionForm.value,
  })
  toast.success('权限已保存')
  await loadPermissions()
}

async function deletePermission(permission: TeamFilePermission) {
  if (!permissionTarget.value) return
  await teamsApi.deleteTeamFilePermission(teamId.value, permissionTarget.value.id, permission.id)
  toast.success('权限已删除')
  await loadPermissions()
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
    toast.error('访问次数限制必须是正整数')
    return
  }
  let password: string | undefined
  if (sharePasswordEnabled.value) {
    password = sharePasswordMode.value === 'generated'
      ? generateSharePassword(generatedPasswordLength.value)
      : sharePassword.value.trim()
  }
  if (password !== undefined && (password.length < 4 || password.length > 16)) {
    toast.error('分享密码长度必须为 4-16 位')
    return
  }
  const { data } = await teamsApi.createTeamShare(teamId.value, {
    fileId: shareTarget.value.id,
    maxVisits: visits,
    password,
    notifyEmail: shareNotifyEmail.value.trim() || undefined,
  })
  const shareUrl = buildShareUrl(data.data.shareCode)
  await navigator.clipboard.writeText(shareUrl).catch(() => {})
  showShare.value = false
  toast.success('团队分享链接已复制到剪贴板')
}

async function runSearch() {
  const q = searchQuery.value.trim()
  if (!q) {
    await store.refresh()
    return
  }
  store.loading = true
  try {
    const { data } = await teamsApi.searchTeamFiles(teamId.value, q, 1, 50)
    store.files = data.data.records
    store.clearSelection()
  } finally {
    store.loading = false
  }
}

async function openMembers() {
  membersOpen.value = true
  await loadMembers()
}

async function loadMembers() {
  membersLoading.value = true
  try {
    const { data } = await teamsApi.listMembers(teamId.value)
    members.value = data.data
  } finally {
    membersLoading.value = false
  }
}

async function addMember() {
  if (!store.canManageMembers || !addUsername.value.trim()) return
  await teamsApi.addMember(teamId.value, addUsername.value.trim(), addRole.value)
  addUsername.value = ''
  addRole.value = 'VIEWER'
  toast.success('成员已添加')
  await loadMembers()
}

async function updateMemberRole(member: TeamMember, role: string) {
  if (!store.canManageMembers || member.role === 'OWNER') return
  await teamsApi.updateMember(teamId.value, member.userId, role as Exclude<TeamRole, 'OWNER'>)
  toast.success('角色已更新')
  await loadMembers()
}

async function removeMember(member: TeamMember) {
  if (!store.canManageMembers || member.role === 'OWNER') return
  const ok = await confirm('移除成员', `确认移除「${member.username}」？`)
  if (!ok) return
  await teamsApi.removeMember(teamId.value, member.userId)
  toast.success('成员已移除')
  await loadMembers()
}

function isOfficeFile(file: FileEntity) {
  const dot = file.fileName.lastIndexOf('.')
  if (dot < 0) return false
  return ['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'ods', 'csv', 'ppt', 'pptx', 'odp']
    .includes(file.fileName.slice(dot + 1).toLowerCase())
}
</script>

<template>
  <div
    class="team-files page-enter"
    @dragenter="onDragEnter"
    @dragleave="onDragLeave"
    @dragover="onDragOver"
    @drop="onDrop"
  >
    <div class="team-files__header">
      <div>
        <h1>{{ store.currentTeam?.name || '团队文件' }}</h1>
        <p>{{ store.currentTeam?.role || '' }} · {{ formatSize(store.currentTeam?.storageUsed || 0) }} / {{ formatSize(store.currentTeam?.storageQuota || 0) }}</p>
      </div>
      <div class="team-files__header-actions">
        <OButton variant="ghost" size="sm" @click="openMembers">成员</OButton>
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamRecycleBin', params: { teamId } })">回收站</OButton>
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamSettings', params: { teamId } })">设置</OButton>
      </div>
    </div>

    <div class="team-files__toolbar">
      <div class="team-files__toolbar-left">
        <OButton v-if="store.canWrite" variant="primary" size="sm" @click="triggerUpload">上传文件</OButton>
        <OButton v-if="store.canWrite && desktopRuntime" variant="ghost" size="sm" @click="triggerFolderUpload">上传文件夹</OButton>
        <OButton v-if="store.canWrite" variant="ghost" size="sm" @click="showNewFolder = true">新建文件夹</OButton>
        <OButton v-if="store.canDelete && store.hasSelection" variant="danger" size="sm" @click="deleteSelected">删除 ({{ store.selectedIds.size }})</OButton>
      </div>
      <div class="team-files__toolbar-right">
        <div class="team-files__search">
          <input v-model="searchQuery" placeholder="搜索团队文件..." @keyup.enter="runSearch" />
          <button type="button" @click="runSearch">搜索</button>
        </div>
        <OButton variant="subtle" size="sm" :icon="true" @click="store.refresh">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><path d="M20.5 16a9 9 0 11-2.5-8.5L23 10"/>
          </svg>
        </OButton>
        <div class="team-files__view-toggle">
          <button :class="{ 'team-files__view-toggle-active': store.viewMode === 'grid' }" @click="store.viewMode = 'grid'">宫格</button>
          <button :class="{ 'team-files__view-toggle-active': store.viewMode === 'list' }" @click="store.viewMode = 'list'">列表</button>
        </div>
      </div>
    </div>

    <input
      ref="fileInput"
      class="team-files__file-input"
      type="file"
      multiple
      aria-hidden="true"
      tabindex="-1"
      @change="handleFileInputChange"
    />

    <nav class="team-files__breadcrumb">
      <template v-for="(item, index) in store.breadcrumb" :key="item.id ?? 'root'">
        <span v-if="index > 0" class="team-files__breadcrumb-sep">/</span>
        <button
          type="button"
          :class="{ 'team-files__breadcrumb-active': index === store.breadcrumb.length - 1 }"
          @click="navigateTo(item)"
        >{{ item.name }}</button>
      </template>
    </nav>

    <div class="team-files__view" @click.self="store.clearSelection">
      <FileGrid
        v-if="store.viewMode === 'grid'"
        :files="store.files"
        :selected-ids="store.selectedIds"
        :loading="store.loading"
        @file-click="(file, event) => store.handleClick(file, event)"
        @file-dblclick="handleDblclick"
        @file-contextmenu="openContextMenu"
      />
      <FileList
        v-else
        :files="store.files"
        :selected-ids="store.selectedIds"
        :loading="store.loading"
        @file-click="(file, event) => store.handleClick(file, event)"
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
    <UploadZone :is-dragging="isDragging" />

    <OModal title="新建文件夹" :open="showNewFolder" @close="showNewFolder = false">
      <OInput v-model="newFolderName" label="文件夹名称" placeholder="输入名称" @keyup.enter="createFolder" />
      <template #footer>
        <OButton variant="ghost" @click="showNewFolder = false">取消</OButton>
        <OButton variant="primary" @click="createFolder">创建</OButton>
      </template>
    </OModal>

    <OModal title="重命名" :open="showRename" @close="showRename = false">
      <OInput v-model="renameName" label="新名称" placeholder="输入新名称" @keyup.enter="submitRename" />
      <template #footer>
        <OButton variant="ghost" @click="showRename = false">取消</OButton>
        <OButton variant="primary" @click="submitRename">确认</OButton>
      </template>
    </OModal>

    <FolderPickerModal
      :open="pickerOpen"
      title="移动到"
      :target-name="pickerTarget?.fileName"
      confirm-text="移动到此处"
      :loading="pickerLoading"
      :team-id="teamId"
      :root-name="store.currentTeam?.name"
      :exclude-folder-id="pickerTarget?.fileType === 'folder' ? pickerTarget.id : undefined"
      @close="pickerOpen = false"
      @confirm="confirmMove"
    />

    <OModal title="文件详情" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailTarget" class="team-files__info">
        <div><span>名称</span><strong>{{ detailTarget.fileName }}</strong></div>
        <div><span>类型</span><strong>{{ detailTarget.fileType === 'folder' ? '文件夹' : detailTarget.mimeType || '文件' }}</strong></div>
        <div><span>大小</span><strong class="font-mono">{{ detailTarget.fileType === 'folder' ? '文件夹' : formatSize(detailTarget.fileSize) }}</strong></div>
        <div><span>版本数</span><strong class="font-mono">{{ detailTarget.versionCount }}</strong></div>
        <div><span>创建时间</span><strong>{{ formatDateFull(detailTarget.createdAt) }}</strong></div>
        <div><span>更新时间</span><strong>{{ formatDateFull(detailTarget.updatedAt) }}</strong></div>
      </div>
    </OModal>

    <OModal title="团队标签" :open="tagOpen" @close="tagOpen = false">
      <div class="tag-manage">
        <div class="tag-manage__target">{{ tagTarget?.fileName }}</div>
        <div v-if="tagsLoading" class="tag-manage__loading"><OSpinner /></div>
        <div v-else-if="teamTags.length === 0" class="tag-manage__empty">暂无团队标签，请先到团队设置创建标签</div>
        <label v-else v-for="tag in teamTags" :key="tag.id" class="tag-check">
          <input v-model="tagSelection[tag.id]" type="checkbox" />
          <span class="tag-check__swatch" :style="{ background: tag.color || '#00d4aa' }" />
          <span>{{ tag.tagName }}</span>
        </label>
      </div>
      <template #footer>
        <OButton variant="ghost" :disabled="tagsLoading" @click="tagOpen = false">取消</OButton>
        <OButton variant="primary" :loading="tagsLoading" @click="saveTags">保存</OButton>
      </template>
    </OModal>

    <OModal title="版本管理" :open="versionsOpen" @close="versionsOpen = false">
      <div class="versions-box">
        <div class="versions-box__target">{{ versionsTarget?.fileName }}</div>
        <div v-if="versionsLoading" class="versions-box__loading"><OSpinner /></div>
        <div v-else-if="versions.length === 0" class="versions-box__empty">暂无历史版本</div>
        <div v-else class="versions-list">
          <div v-for="version in versions" :key="version.id" class="version-row">
            <div>
              <div class="version-row__title">版本 {{ version.versionNumber }}</div>
              <div class="version-row__meta font-mono">{{ formatSize(version.fileSize) }} · {{ formatDateFull(version.createdAt) }}</div>
            </div>
            <div class="version-row__actions">
              <OButton variant="ghost" size="sm" @click="downloadVersion(version.versionNumber)">下载</OButton>
              <OButton v-if="store.canWrite" variant="ghost" size="sm" @click="restoreVersion(version.versionNumber)">恢复</OButton>
              <OButton v-if="store.canDelete" variant="danger" size="sm" @click="deleteVersion(version.versionNumber)">删除</OButton>
            </div>
          </div>
        </div>
      </div>
    </OModal>

    <OModal title="创建团队分享" :open="showShare" data-testid="team-share-modal" @close="showShare = false">
      <div class="share-form">
        <div class="share-form__name">{{ shareTarget?.fileName }}</div>
        <OInput
          v-model="shareMaxVisits"
          label="访问次数限制"
          type="number"
          placeholder="留空表示不限"
          @keyup.enter="submitShare"
        />
        <OInput
          v-model="shareNotifyEmail"
          label="发送给"
          type="email"
          placeholder="可选，填写邮箱后发送通知"
          @keyup.enter="submitShare"
        />
        <label class="share-password-toggle">
          <input v-model="sharePasswordEnabled" type="checkbox" />
          <span class="share-password-toggle__box">
            <span />
          </span>
          访问密码
        </label>
        <div v-if="sharePasswordEnabled" class="share-password">
          <div class="share-password__segments" aria-label="密码设置方式">
            <button
              type="button"
              :class="{ 'share-password__segment--active': sharePasswordMode === 'manual' }"
              @click="sharePasswordMode = 'manual'"
            >
              手动输入
            </button>
            <button
              type="button"
              :class="{ 'share-password__segment--active': sharePasswordMode === 'generated' }"
              @click="sharePasswordMode = 'generated'"
            >
              自动生成
            </button>
          </div>
          <OInput
            v-if="sharePasswordMode === 'manual'"
            v-model="sharePassword"
            label="访问密码"
            type="text"
            placeholder="4-16 位"
            @keyup.enter="submitShare"
          />
          <div v-else class="share-password__generated">
            <div class="share-password__label">密码长度</div>
            <div class="share-password__segments" aria-label="密码长度">
              <button
                type="button"
                :class="{ 'share-password__segment--active': generatedPasswordLength === 4 }"
                @click="generatedPasswordLength = 4"
              >
                4 位
              </button>
              <button
                type="button"
                :class="{ 'share-password__segment--active': generatedPasswordLength === 8 }"
                @click="generatedPasswordLength = 8"
              >
                8 位
              </button>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="showShare = false">取消</OButton>
        <OButton variant="primary" @click="submitShare">创建并复制链接</OButton>
      </template>
    </OModal>

    <OModal title="团队成员" :open="membersOpen" width="620px" @close="membersOpen = false">
      <div class="team-members">
        <div v-if="store.canManageMembers" class="team-members__add">
          <input v-model="addUsername" placeholder="用户名" @keyup.enter="addMember" />
          <select v-model="addRole">
            <option value="VIEWER">Viewer</option>
            <option value="EDITOR">Editor</option>
            <option v-if="store.currentTeam?.role === 'OWNER'" value="ADMIN">Admin</option>
          </select>
          <OButton variant="primary" size="sm" @click="addMember">添加</OButton>
        </div>
        <div v-if="membersLoading" class="team-members__loading"><OSpinner /></div>
        <div v-else class="team-members__list">
          <div v-for="member in members" :key="member.userId" class="team-members__row">
            <div>
              <div class="team-members__name">{{ member.username }}</div>
              <div class="team-members__email">{{ member.email || '-' }}</div>
            </div>
            <select
              :value="member.role"
              :disabled="!store.canManageMembers || member.role === 'OWNER'"
              @change="event => updateMemberRole(member, (event.target as HTMLSelectElement).value)"
            >
              <option value="OWNER" disabled>Owner</option>
              <option value="ADMIN">Admin</option>
              <option value="EDITOR">Editor</option>
              <option value="VIEWER">Viewer</option>
            </select>
            <OButton
              v-if="store.canManageMembers && member.role !== 'OWNER'"
              variant="danger"
              size="sm"
              @click="removeMember(member)"
            >
              移除
            </OButton>
          </div>
        </div>
      </div>
    </OModal>

    <OModal title="文件权限" :open="permissionOpen" width="680px" @close="permissionOpen = false">
      <div class="permission-box">
        <div class="permission-box__target">{{ permissionTarget?.fileName }}</div>
        <div v-if="permissionEffective" class="permission-box__effective">
          <span>当前有效权限</span>
          <strong>
            {{ permissionEffective.canRead ? '读' : '-' }} /
            {{ permissionEffective.canWrite ? '写' : '-' }} /
            {{ permissionEffective.canShare ? '分享' : '-' }} /
            {{ permissionEffective.canDelete ? '删除' : '-' }} /
            {{ permissionEffective.canManage ? '管理' : '-' }}
          </strong>
        </div>
        <div class="permission-box__form">
          <select v-model="permissionSubjectType">
            <option value="ROLE">按角色</option>
            <option value="USER">按用户</option>
          </select>
          <select v-if="permissionSubjectType === 'ROLE'" v-model="permissionSubjectRole">
            <option value="VIEWER">Viewer</option>
            <option value="EDITOR">Editor</option>
            <option value="ADMIN">Admin</option>
          </select>
          <input v-else v-model="permissionSubjectUserId" inputmode="numeric" placeholder="用户 ID" />
          <label><input v-model="permissionForm.canRead" type="checkbox" /> 读</label>
          <label><input v-model="permissionForm.canWrite" type="checkbox" /> 写</label>
          <label><input v-model="permissionForm.canShare" type="checkbox" /> 分享</label>
          <label><input v-model="permissionForm.canDelete" type="checkbox" /> 删除</label>
          <label><input v-model="permissionForm.canManage" type="checkbox" /> 管理</label>
          <OButton variant="primary" size="sm" @click="savePermission">保存</OButton>
        </div>
        <div v-if="permissionsLoading" class="permission-box__loading"><OSpinner /></div>
        <div v-else-if="permissions.length === 0" class="permission-box__empty">暂无权限覆盖</div>
        <div v-else class="permission-list">
          <div v-for="permission in permissions" :key="permission.id" class="permission-row">
            <div>
              <strong>{{ permission.subjectType === 'ROLE' ? permission.subjectRole : `用户 ${permission.subjectUserId}` }}</strong>
              <span>{{ permission.canRead ? '读' : '-' }} / {{ permission.canWrite ? '写' : '-' }} / {{ permission.canShare ? '分享' : '-' }} / {{ permission.canDelete ? '删' : '-' }} / {{ permission.canManage ? '管' : '-' }}</span>
            </div>
            <OButton variant="danger" size="sm" @click="deletePermission(permission)">删除</OButton>
          </div>
        </div>
      </div>
    </OModal>
  </div>
</template>

<style scoped>
.team-files {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
.team-files__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.team-files__header h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.2;
}
.team-files__header p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.team-files__header-actions,
.team-files__toolbar,
.team-files__toolbar-left,
.team-files__toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.team-files__toolbar {
  justify-content: space-between;
}
.team-files__search {
  display: flex;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
  background: var(--bg-elevated);
}
.team-files__search input {
  width: 190px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  padding: 7px 9px;
  outline: none;
  font-family: var(--font-sans);
  font-size: 13px;
}
.team-files__search button,
.team-files__view-toggle button {
  border: 0;
  border-left: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  padding: 7px 10px;
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
}
.team-files__view-toggle {
  display: flex;
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
}
.team-files__view-toggle button:first-child {
  border-left: 0;
}
.team-files__view-toggle-active {
  color: var(--accent) !important;
  background: var(--accent-dim) !important;
}
.team-files__file-input {
  position: fixed;
  left: 0;
  bottom: 0;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
.team-files__breadcrumb {
  display: flex;
  align-items: center;
  gap: 5px;
  min-height: 28px;
  flex-wrap: wrap;
}
.team-files__breadcrumb button {
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  border-radius: 5px;
  padding: 3px 6px;
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 13px;
}
.team-files__breadcrumb button:hover {
  background: var(--bg-elevated);
  color: var(--text-primary);
}
.team-files__breadcrumb-active {
  color: var(--text-primary) !important;
  font-weight: 500;
}
.team-files__breadcrumb-sep {
  color: var(--text-muted);
}
.team-files__view {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.team-files__info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.team-files__info div {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 10px;
  font-size: 13px;
}
.team-files__info span,
.team-members__email {
  color: var(--text-secondary);
}
.versions-box__empty,
.versions-box__loading {
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}
.versions-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.versions-box__target {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.versions-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
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
.version-row__title {
  font-size: 13px;
  font-weight: 500;
}
.version-row__meta {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.version-row__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.team-files__info strong {
  color: var(--text-primary);
  font-weight: 500;
  overflow-wrap: anywhere;
}
.tag-manage {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tag-manage__target,
.tag-manage__empty {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.tag-manage__loading {
  display: flex;
  justify-content: center;
  padding: 20px;
}
.tag-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  color: var(--text-primary);
  font-size: 13px;
}
.tag-check input {
  margin: 0;
}
.tag-check__swatch {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid var(--border);
}
.share-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.share-form__name {
  font-size: 13px;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.share-password-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}
.share-password-toggle input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.share-password-toggle__box {
  width: 34px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
  padding: 2px;
  transition: all var(--fast);
}
.share-password-toggle__box span {
  display: block;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: all var(--fast);
}
.share-password-toggle input:checked + .share-password-toggle__box {
  background: var(--accent-dim);
  border-color: var(--accent);
}
.share-password-toggle input:checked + .share-password-toggle__box span {
  transform: translateX(14px);
  background: var(--accent);
}
.share-password {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.share-password__label {
  color: var(--text-secondary);
  font-size: 12px;
}
.share-password__generated {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.share-password__segments {
  display: inline-flex;
  align-self: flex-start;
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
}
.share-password__segments button {
  border: 0;
  border-left: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  padding: 6px 10px;
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
}
.share-password__segments button:first-child {
  border-left: 0;
}
.share-password__segments button:hover {
  color: var(--text-primary);
}
.share-password__segments .share-password__segment--active {
  color: var(--accent);
  background: var(--accent-dim);
}
.team-members {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.team-members__add {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px auto;
  gap: 8px;
}
.team-members input,
.team-members select {
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
  font-family: var(--font-sans);
}
.team-members__loading {
  text-align: center;
  padding: 24px;
}
.team-members__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.team-members__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px auto;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.team-members__name {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
}
.team-members__email {
  font-size: 11px;
  margin-top: 2px;
}
.permission-box {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.permission-box__target {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.permission-box__effective {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  font-size: 12px;
}
.permission-box__effective span {
  color: var(--text-secondary);
}
.permission-box__effective strong {
  color: var(--accent);
  font-weight: 600;
}
.permission-box__form {
  display: grid;
  grid-template-columns: 96px minmax(120px, 1fr) repeat(5, auto) auto;
  align-items: center;
  gap: 8px;
}
.permission-box__form select,
.permission-box__form input[inputmode="numeric"] {
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 9px;
}
.permission-box__form label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.permission-box__form input[type="checkbox"] {
  accent-color: var(--accent);
}
.permission-box__loading,
.permission-box__empty {
  color: var(--text-secondary);
  text-align: center;
  padding: 20px;
  font-size: 13px;
}
.permission-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.permission-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.permission-row strong {
  display: block;
  font-size: 13px;
  margin-bottom: 2px;
}
.permission-row span {
  color: var(--text-secondary);
  font-size: 12px;
}
@media (max-width: 900px) {
  .permission-box__form {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
