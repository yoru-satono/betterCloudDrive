import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as teamsApi from '@/api/teams'
import type { BreadcrumbItem, FileEntity } from '@/types/file'
import type { Team, TeamEffectivePermission } from '@/types/team'

export const useTeamFilesStore = defineStore('teamFiles', () => {
  const currentTeam = ref<Team | null>(null)
  const files = ref<FileEntity[]>([])
  const total = ref(0)
  const page = ref(1)
  const pages = ref(0)
  const loading = ref(false)
  const currentParentId = ref<number | null>(null)
  const breadcrumb = ref<BreadcrumbItem[]>([{ id: null, name: __t('team.teamfiles.teamDocuments') }])
  const viewMode = ref<'grid' | 'list'>('grid')
  const selectedIds = ref<Set<number>>(new Set())
  const sortBy = ref('fileName')
  const order = ref('asc')
  const currentPermission = ref<TeamEffectivePermission>({ canRead: true, canWrite: false, canShare: false, canDelete: false, canManage: false })
  const filePermissions = ref<Record<number, TeamEffectivePermission>>({})

  const canWrite = computed(() => currentPermission.value.canWrite)
  const canShare = computed(() => currentPermission.value.canShare)
  const canDelete = computed(() => currentPermission.value.canDelete)
  const canManageFiles = computed(() => currentPermission.value.canManage)
  const canManageMembers = computed(() => ['OWNER', 'ADMIN'].includes(currentTeam.value?.role || ''))
  const isOwner = computed(() => currentTeam.value?.role === 'OWNER')
  const hasSelection = computed(() => selectedIds.value.size > 0)
  const selectedFiles = computed(() => files.value.filter(file => selectedIds.value.has(file.id)))

  async function loadTeam(teamId: number) {
    const { data } = await teamsApi.getTeam(teamId)
    currentTeam.value = data.data
    if (breadcrumb.value.length === 1) {
      breadcrumb.value = [{ id: null, name: data.data.name }]
    } else {
      breadcrumb.value[0] = { id: null, name: data.data.name }
    }
  }

  async function fetchFiles(teamId: number, parentId?: number | null, p = 1) {
    loading.value = true
    try {
      const resolvedParentId = parentId !== undefined ? parentId : currentParentId.value
      const { data } = await teamsApi.listTeamFiles(teamId, {
        parentId: resolvedParentId === null ? undefined : resolvedParentId,
        page: p,
        size: 50,
        sortBy: sortBy.value,
        order: order.value,
      })
      files.value = data.data.records
      filePermissions.value = {}
      total.value = data.data.total
      page.value = data.data.page
      pages.value = data.data.pages
      if (parentId !== undefined) currentParentId.value = parentId ?? null
      await loadCurrentPermission(teamId, currentParentId.value)
    } finally {
      loading.value = false
    }
  }

  async function loadCurrentPermission(teamId: number, fileId: number | null) {
    if (fileId == null) {
      currentPermission.value = {
        canRead: true,
        canWrite: ['OWNER', 'ADMIN', 'EDITOR'].includes(currentTeam.value?.role || ''),
        canShare: ['OWNER', 'ADMIN', 'EDITOR'].includes(currentTeam.value?.role || ''),
        canDelete: ['OWNER', 'ADMIN', 'EDITOR'].includes(currentTeam.value?.role || ''),
        canManage: ['OWNER', 'ADMIN'].includes(currentTeam.value?.role || ''),
      }
      return
    }
    const { data } = await teamsApi.getTeamFileEffectivePermission(teamId, fileId)
    currentPermission.value = data.data
  }

  function permissionForFile(fileId: number) {
    return filePermissions.value[fileId] ?? currentPermission.value
  }

  function setFilePermission(fileId: number, permission: TeamEffectivePermission) {
    filePermissions.value = { ...filePermissions.value, [fileId]: permission }
    if (currentParentId.value === fileId) {
      currentPermission.value = permission
    }
  }

  async function loadFilePermission(teamId: number, fileId: number, force = false) {
    if (!force && filePermissions.value[fileId]) {
      return filePermissions.value[fileId]
    }
    const { data } = await teamsApi.getTeamFileEffectivePermission(teamId, fileId)
    setFilePermission(fileId, data.data)
    return data.data
  }

  async function openDirectory(teamId: number, folderId: number | null, folderHint?: FileEntity) {
    if (!currentTeam.value || currentTeam.value.id !== teamId) {
      await loadTeam(teamId)
    }
    breadcrumb.value = folderId === null
      ? [{ id: null, name: currentTeam.value?.name || __t('team.teamfiles.teamDocuments') }]
      : await buildBreadcrumb(teamId, folderId, folderHint)
    await fetchFiles(teamId, folderId)
    clearSelection()
  }

  async function buildBreadcrumb(teamId: number, folderId: number, folderHint?: FileEntity) {
    const items: BreadcrumbItem[] = []
    const visited = new Set<number>()
    let current: FileEntity | null = folderHint?.id === folderId ? folderHint : null
    let cursor: number | null = folderId

    while (cursor !== null && !visited.has(cursor)) {
      visited.add(cursor)
      if (!current || current.id !== cursor) {
        const { data } = await teamsApi.getTeamFile(teamId, cursor)
        current = data.data
      }
      items.unshift({ id: current.id, name: current.fileName })
      cursor = current.parentId
      current = null
    }

    return [{ id: null, name: currentTeam.value?.name || __t('team.teamfiles.teamDocuments') }, ...items]
  }

  function enterFolder(teamId: number, folder: FileEntity) {
    return openDirectory(teamId, folder.id, folder)
  }

  async function refresh() {
    if (!currentTeam.value) return
    await fetchFiles(currentTeam.value.id, currentParentId.value)
  }

  function refreshIfCurrent(teamId: number) {
    if (currentTeam.value?.id === teamId) {
      void refresh()
    }
  }

  function handleClick(file: FileEntity, event: MouseEvent) {
    if (event.metaKey || event.ctrlKey) {
      toggleSelect(file.id)
      return
    }
    if (event.shiftKey && selectedIds.value.size > 0) {
      const ids = files.value.map(item => item.id)
      const lastSelected = [...selectedIds.value].pop()!
      const from = ids.indexOf(lastSelected)
      const to = ids.indexOf(file.id)
      const [start, end] = from < to ? [from, to] : [to, from]
      ids.slice(start, end + 1).forEach(id => selectedIds.value.add(id))
      return
    }
    clearSelection()
    toggleSelect(file.id)
  }

  function toggleSelect(id: number) {
    if (selectedIds.value.has(id)) selectedIds.value.delete(id)
    else selectedIds.value.add(id)
  }

  function clearSelection() {
    selectedIds.value.clear()
  }

  return {
    currentTeam,
    files,
    total,
    page,
    pages,
    loading,
    currentParentId,
    currentPermission,
    filePermissions,
    breadcrumb,
    viewMode,
    selectedIds,
    sortBy,
    order,
    canWrite,
    canShare,
    canDelete,
    canManageFiles,
    canManageMembers,
    isOwner,
    hasSelection,
    selectedFiles,
    loadTeam,
    fetchFiles,
    loadCurrentPermission,
    loadFilePermission,
    permissionForFile,
    setFilePermission,
    openDirectory,
    enterFolder,
    refresh,
    refreshIfCurrent,
    handleClick,
    clearSelection,
    toggleSelect,
  }
})
