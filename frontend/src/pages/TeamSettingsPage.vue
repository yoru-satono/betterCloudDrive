<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye } from '@lucide/vue'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OInput from '@/components/base/OInput.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useFormatters } from '@/composables/useFormatters'
import * as teamsApi from '@/api/teams'
import { saveBlobResponse } from '@/api/download'
import { useAuthStore } from '@/stores/auth'
import { useTeamsStore } from '@/stores/teams'
import { buildShareUrl } from '@/config/runtime'
import { toast } from 'vue-sonner'
import type { TagEntity } from '@/types/tag'
import type { FileEntity } from '@/types/file'
import type { ShareLinkEntity } from '@/types/share'
import type { Team, TeamActivity, TeamInvite, TeamMember, TeamOperationLog, TeamRole, TeamTask } from '@/types/team'
import { i18n, resolveMessage } from '@/i18n'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const teamsStore = useTeamsStore()
const { confirm } = useConfirm()
const { formatDateFull, formatSize } = useFormatters()
const t = i18n.global.t

const teamId = computed(() => Number(route.params.teamId))
const team = ref<Team | null>(null)
const members = ref<TeamMember[]>([])
const shares = ref<ShareLinkEntity[]>([])
const invites = ref<TeamInvite[]>([])
const activities = ref<TeamActivity[]>([])
const auditLogs = ref<TeamOperationLog[]>([])
const teamTasks = ref<TeamTask[]>([])
const teamTags = ref<TagEntity[]>([])
const teamFavorites = ref<FileEntity[]>([])
const tagFiles = ref<FileEntity[]>([])
const selectedTag = ref<TagEntity | null>(null)
const loading = ref(false)
const membersLoading = ref(false)
const sharesLoading = ref(false)
const invitesLoading = ref(false)
const activitiesLoading = ref(false)
const auditLoading = ref(false)
const tasksLoading = ref(false)
const tagsLoading = ref(false)
const favoritesLoading = ref(false)
const tagFilesLoading = ref(false)
const teamName = ref('')
const addUsername = ref('')
const addRole = ref<Exclude<TeamRole, 'OWNER'>>('VIEWER')
const inviteEmail = ref('')
const inviteRole = ref<Exclude<TeamRole, 'OWNER'>>('VIEWER')
const newTagName = ref('')
const newTagColor = ref('#00d4aa')
const detailOpen = ref(false)
const detailShare = ref<ShareLinkEntity | null>(null)
const detailPassword = ref('')
const loadingPasswordShareId = ref<number | null>(null)
const editOpen = ref(false)
const editShare = ref<ShareLinkEntity | null>(null)
const editMaxVisits = ref('')
const editExpireAt = ref('')
const editPassword = ref('')
const editClearPassword = ref(false)
const favoritesQuery = ref('')
const favoritesPage = ref(1)
const favoritesTotal = ref(0)
const tagFilesQuery = ref('')
const tagFilesPage = ref(1)
const tagFilesTotal = ref(0)
const TEAM_COLLECTION_PAGE_SIZE = 10
const TEAM_ACTIVITY_PAGE_SIZE = 10
const TEAM_AUDIT_PAGE_SIZE = 20
const activitiesPage = ref(1)
const activitiesTotal = ref(0)
const auditPage = ref(1)
const auditTotal = ref(0)
const auditAction = ref('')
const auditTargetType = ref('')
const auditResult = ref<'' | '1' | '0'>('')
const tasksPage = ref(1)
const tasksTotal = ref(0)
const taskStatus = ref('')
const taskType = ref('')
const TEAM_TASK_PAGE_SIZE = 10

const canManage = computed(() => ['OWNER', 'ADMIN'].includes(team.value?.role || ''))
const isOwner = computed(() => team.value?.role === 'OWNER')
const favoritesTotalPages = computed(() => Math.max(1, Math.ceil(favoritesTotal.value / TEAM_COLLECTION_PAGE_SIZE)))
const tagFilesTotalPages = computed(() => Math.max(1, Math.ceil(tagFilesTotal.value / TEAM_COLLECTION_PAGE_SIZE)))
const activitiesTotalPages = computed(() => Math.max(1, Math.ceil(activitiesTotal.value / TEAM_ACTIVITY_PAGE_SIZE)))
const auditTotalPages = computed(() => Math.max(1, Math.ceil(auditTotal.value / TEAM_AUDIT_PAGE_SIZE)))
const tasksTotalPages = computed(() => Math.max(1, Math.ceil(tasksTotal.value / TEAM_TASK_PAGE_SIZE)))
const quotaPercent = computed(() => {
  const quota = team.value?.storageQuota || 0
  if (quota <= 0) return 0
  return Math.min(100, Math.round(((team.value?.storageUsed || 0) / quota) * 100))
})
const quotaState = computed(() => quotaPercent.value >= 100 ? 'danger' : quotaPercent.value >= 90 ? 'warning' : quotaPercent.value >= 80 ? 'notice' : 'normal')

async function load() {
  if (!Number.isFinite(teamId.value)) return
  loading.value = true
  try {
    const { data } = await teamsApi.getTeam(teamId.value)
    team.value = data.data
    teamName.value = data.data.name
    teamsStore.upsertTeam(data.data)
    await Promise.all([
      loadMembers(),
      loadShares(),
      loadTeamTags(),
      loadTeamFavorites(),
      loadTeamActivities(),
      loadTeamTasks(),
      canManage.value ? loadInvites() : Promise.resolve(),
      canManage.value ? loadAuditLogs() : Promise.resolve(),
    ])
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  membersLoading.value = true
  try {
    members.value = await teamsApi.listAllMembers(teamId.value)
  } finally {
    membersLoading.value = false
  }
}

async function loadShares() {
  sharesLoading.value = true
  try {
    const { data } = await teamsApi.listTeamShares(teamId.value, 1, 100)
    shares.value = data.data.records
  } finally {
    sharesLoading.value = false
  }
}

async function loadInvites() {
  if (!canManage.value) return
  invitesLoading.value = true
  try {
    invites.value = await teamsApi.listAllInvites(teamId.value)
  } finally {
    invitesLoading.value = false
  }
}

async function loadTeamActivities() {
  activitiesLoading.value = true
  try {
    const { data } = await teamsApi.listTeamActivities(
      teamId.value,
      activitiesPage.value,
      TEAM_ACTIVITY_PAGE_SIZE,
    )
    activities.value = data.data.records
    activitiesTotal.value = data.data.total
  } finally {
    activitiesLoading.value = false
  }
}

function goActivitiesPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), activitiesTotalPages.value)
  if (normalizedPage === activitiesPage.value) return
  activitiesPage.value = normalizedPage
  loadTeamActivities()
}

async function loadAuditLogs() {
  if (!canManage.value) return
  auditLoading.value = true
  try {
    const { data } = await teamsApi.listTeamAuditLogs(teamId.value, {
      page: auditPage.value,
      size: TEAM_AUDIT_PAGE_SIZE,
      action: auditAction.value.trim() || undefined,
      targetType: auditTargetType.value.trim() || undefined,
      result: auditResult.value === '' ? undefined : Number(auditResult.value),
    })
    auditLogs.value = data.data.records
    auditTotal.value = data.data.total
  } finally {
    auditLoading.value = false
  }
}

async function exportAuditLogs() {
  await teamsApi.queueTeamAuditExport(teamId.value, {
    action: auditAction.value.trim() || undefined,
    targetType: auditTargetType.value.trim() || undefined,
    result: auditResult.value === '' ? undefined : Number(auditResult.value),
  })
  toast.success(t('teamSettings.toast.auditSubmitted'))
  await loadTeamTasks()
}

function filterAuditLogs() {
  auditPage.value = 1
  loadAuditLogs()
}

function goAuditPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), auditTotalPages.value)
  if (normalizedPage === auditPage.value) return
  auditPage.value = normalizedPage
  loadAuditLogs()
}

async function loadTeamTasks() {
  tasksLoading.value = true
  try {
    const { data } = await teamsApi.listTeamTasks(teamId.value, {
      page: tasksPage.value,
      size: TEAM_TASK_PAGE_SIZE,
      status: taskStatus.value || undefined,
      type: taskType.value.trim() || undefined,
    })
    teamTasks.value = data.data.records
    tasksTotal.value = data.data.total
  } finally {
    tasksLoading.value = false
  }
}

function filterTeamTasks() {
  tasksPage.value = 1
  loadTeamTasks()
}

function goTaskPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), tasksTotalPages.value)
  if (normalizedPage === tasksPage.value) return
  tasksPage.value = normalizedPage
  loadTeamTasks()
}

async function retryTask(task: TeamTask) {
  await teamsApi.retryTeamTask(teamId.value, task.id)
  toast.success(t('teamSettings.toast.taskRequeued'))
  await loadTeamTasks()
}

async function downloadTaskResult(task: TeamTask) {
  const response = await teamsApi.downloadTeamTaskResult(teamId.value, task.id)
  await saveBlobResponse(response, task.resultFileName || `team-${teamId.value}-task-${task.id}.csv`)
}

async function loadTeamTags() {
  tagsLoading.value = true
  try {
    const { data } = await teamsApi.listTeamTags(teamId.value)
    teamTags.value = data.data
  } finally {
    tagsLoading.value = false
  }
}

async function loadTeamFavorites() {
  favoritesLoading.value = true
  try {
    const { data } = await teamsApi.listTeamFavorites(
      teamId.value,
      favoritesPage.value,
      TEAM_COLLECTION_PAGE_SIZE,
      favoritesQuery.value.trim() || undefined,
    )
    teamFavorites.value = data.data.records
    favoritesTotal.value = data.data.total
  } finally {
    favoritesLoading.value = false
  }
}

async function loadTagFiles() {
  if (!selectedTag.value) return
  tagFilesLoading.value = true
  try {
    const { data } = await teamsApi.listTeamFilesByTag(
      teamId.value,
      selectedTag.value.id,
      tagFilesPage.value,
      TEAM_COLLECTION_PAGE_SIZE,
      tagFilesQuery.value.trim() || undefined,
    )
    tagFiles.value = data.data.records
    tagFilesTotal.value = data.data.total
  } finally {
    tagFilesLoading.value = false
  }
}

function searchFavorites() {
  favoritesPage.value = 1
  loadTeamFavorites()
}

function goFavoritesPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), favoritesTotalPages.value)
  if (normalizedPage === favoritesPage.value) return
  favoritesPage.value = normalizedPage
  loadTeamFavorites()
}

function openTagFiles(tag: TagEntity) {
  selectedTag.value = tag
  tagFilesQuery.value = ''
  tagFilesPage.value = 1
  loadTagFiles()
}

function searchTagFiles() {
  tagFilesPage.value = 1
  loadTagFiles()
}

function goTagFilesPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), tagFilesTotalPages.value)
  if (normalizedPage === tagFilesPage.value) return
  tagFilesPage.value = normalizedPage
  loadTagFiles()
}

function openTeamFileLocation(file: FileEntity) {
  if (file.fileType === 'folder') {
    router.push({ name: 'TeamFolder', params: { teamId: teamId.value, folderId: file.id } })
  } else if (file.parentId) {
    router.push({ name: 'TeamFolder', params: { teamId: teamId.value, folderId: file.parentId } })
  } else {
    router.push({ name: 'TeamFiles', params: { teamId: teamId.value } })
  }
}

async function saveTeamName() {
  if (!canManage.value || !teamName.value.trim() || teamName.value.trim() === team.value?.name) return
  const { data } = await teamsApi.updateTeam(teamId.value, teamName.value.trim())
  team.value = data.data
  teamName.value = data.data.name
  teamsStore.upsertTeam(data.data)
  toast.success(t('teamSettings.toast.nameUpdated'))
}

async function addMember() {
  if (!canManage.value || !addUsername.value.trim()) return
  await teamsApi.addMember(teamId.value, addUsername.value.trim(), addRole.value)
  addUsername.value = ''
  addRole.value = 'VIEWER'
  toast.success(t('teamSettings.toast.memberAdded'))
  await loadMembers()
}

async function createInvite() {
  if (!canManage.value || !inviteEmail.value.trim()) return
  await teamsApi.createInvite(teamId.value, inviteEmail.value.trim(), inviteRole.value)
  inviteEmail.value = ''
  inviteRole.value = 'VIEWER'
  toast.success(t('teamSettings.toast.inviteSent'))
  await loadInvites()
}

async function resendInvite(invite: TeamInvite) {
  await teamsApi.resendInvite(teamId.value, invite.id)
  toast.success(t('teamSettings.toast.inviteResent'))
  await loadInvites()
}

async function cancelInvite(invite: TeamInvite) {
  const ok = await confirm(t('teamSettings.confirm.cancelInviteTitle'), t('teamSettings.confirm.cancelInvite', { email: invite.email }))
  if (!ok) return
  await teamsApi.cancelInvite(teamId.value, invite.id)
  toast.success(t('teamSettings.toast.inviteCanceled'))
  await loadInvites()
}

async function createTeamTag() {
  if (!canManage.value || !newTagName.value.trim()) return
  await teamsApi.createTeamTag(teamId.value, newTagName.value.trim(), newTagColor.value)
  newTagName.value = ''
  toast.success(t('teamSettings.toast.tagCreated'))
  await loadTeamTags()
}

async function deleteTeamTag(tag: TagEntity) {
  if (!canManage.value) return
  const ok = await confirm(t('teamSettings.confirm.deleteTagTitle'), t('teamSettings.confirm.deleteTag', { name: tag.tagName }))
  if (!ok) return
  await teamsApi.deleteTeamTag(teamId.value, tag.id)
  toast.success(t('teamSettings.toast.tagDeleted'))
  if (selectedTag.value?.id === tag.id) {
    selectedTag.value = null
    tagFiles.value = []
    tagFilesTotal.value = 0
  }
  await loadTeamTags()
}

async function updateMemberRole(member: TeamMember, role: string) {
  if (!canManage.value || member.role === 'OWNER') return
  await teamsApi.updateMember(teamId.value, member.userId, role as Exclude<TeamRole, 'OWNER'>)
  toast.success(t('teamSettings.toast.roleUpdated'))
  await loadMembers()
}

async function removeMember(member: TeamMember) {
  if (!canManage.value || member.role === 'OWNER') return
  const ok = await confirm(t('teamSettings.confirm.removeMemberTitle'), t('teamSettings.confirm.removeMember', { name: member.username }))
  if (!ok) return
  await teamsApi.removeMember(teamId.value, member.userId)
  toast.success(t('teamSettings.toast.memberRemoved'))
  await loadMembers()
}

async function transferOwner(member: TeamMember) {
  if (!isOwner.value || member.role === 'OWNER') return
  const ok = await confirm(t('teamSettings.confirm.transferTitle'), t('teamSettings.confirm.transfer', { name: member.username }))
  if (!ok) return
  const { data } = await teamsApi.transferTeamOwner(teamId.value, member.userId)
  team.value = data.data
  teamsStore.upsertTeam(data.data)
  toast.success(t('teamSettings.toast.ownerTransferred'))
  await Promise.all([loadMembers(), loadShares(), loadInvites(), loadAuditLogs()])
}

async function leaveTeam() {
  if (isOwner.value) return
  const ok = await confirm(t('teamSettings.confirm.leaveTitle'), t('teamSettings.confirm.leave'))
  if (!ok) return
  await teamsApi.leaveTeam(teamId.value)
  teamsStore.removeTeam(teamId.value)
  toast.success(t('teamSettings.toast.left'))
  await router.push({ name: 'Teams' })
}

async function deleteTeam() {
  if (!isOwner.value || !team.value) return
  const ok = await confirm(t('teamSettings.confirm.deleteTeamTitle'), t('teamSettings.confirm.deleteTeam', { name: team.value.name }))
  if (!ok) return
  await teamsApi.deleteTeam(teamId.value)
  teamsStore.removeTeam(teamId.value)
  toast.success(t('teamSettings.toast.teamDeleted'))
  await router.push({ name: 'Teams' })
}

function canManageShare(share: ShareLinkEntity) {
  return canManage.value || share.userId === auth.user?.id
}

function isExpired(share: ShareLinkEntity) {
  return share.expireAt && new Date(share.expireAt) < new Date()
}

async function copyLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(buildShareUrl(share.shareCode)).catch(() => {})
  toast.success(t('teamSettings.toast.linkCopied'))
}

async function openDetail(share: ShareLinkEntity) {
  const { data } = await teamsApi.getTeamShare(teamId.value, share.id)
  detailShare.value = data.data
  detailPassword.value = ''
  detailOpen.value = true
}

async function loadSharePassword(share: ShareLinkEntity) {
  loadingPasswordShareId.value = share.id
  try {
    const { data } = await teamsApi.getTeamSharePassword(teamId.value, share.id)
    detailShare.value = share
    detailPassword.value = data.data.password ?? ''
    detailOpen.value = true
  } finally {
    loadingPasswordShareId.value = null
  }
}

function openEdit(share: ShareLinkEntity) {
  editShare.value = share
  editMaxVisits.value = share.maxVisits ? String(share.maxVisits) : ''
  editExpireAt.value = share.expireAt ? new Date(share.expireAt).toISOString().slice(0, 16) : ''
  editPassword.value = ''
  editClearPassword.value = false
  editOpen.value = true
}

async function submitEdit() {
  if (!editShare.value || !canManageShare(editShare.value)) return
  const maxVisits = editMaxVisits.value.trim() ? Number(editMaxVisits.value) : undefined
  if (maxVisits !== undefined && (!Number.isInteger(maxVisits) || maxVisits < 1)) {
    toast.error(t('teamSettings.validation.maxVisits'))
    return
  }
  const password = editPassword.value.trim()
  if (!editClearPassword.value && password && (password.length < 4 || password.length > 16)) {
    toast.error(t('teamSettings.validation.passwordLength'))
    return
  }
  const clearMaxVisits = !editMaxVisits.value.trim()
  const clearExpireAt = !editExpireAt.value
  await teamsApi.updateTeamShare(teamId.value, editShare.value.id, {
    maxVisits,
    expireAt: clearExpireAt ? undefined : new Date(editExpireAt.value).getTime(),
    password: editClearPassword.value ? undefined : password || undefined,
    clearPassword: editClearPassword.value,
    clearExpireAt,
    clearMaxVisits,
  })
  editOpen.value = false
  toast.success(t('teamSettings.toast.shareUpdated'))
  await loadShares()
}

async function deleteShare(share: ShareLinkEntity) {
  if (!canManageShare(share)) return
  const ok = await confirm(t('teamSettings.confirm.cancelShareTitle'), t('teamSettings.confirm.cancelShare'))
  if (!ok) return
  await teamsApi.deleteTeamShare(teamId.value, share.id)
  toast.success(t('teamSettings.toast.shareCanceled'))
  await loadShares()
}

watch(teamId, () => load())

onMounted(async () => {
  if (!auth.user) await auth.fetchMe()
  await load()
})
</script>

<template>
  <div class="team-settings page-enter">
    <div class="team-settings__header">
      <div>
        <h2>{{ t('teamSettings.ui.title') }}</h2>
        <p class="text-muted">{{ team?.name || t('teamSettings.ui.team') }} · {{ team?.role || '' }}</p>
      </div>
      <div class="team-settings__header-actions">
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamFiles', params: { teamId } })">{{ t('teamSettings.ui.backToFiles') }}</OButton>
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamRecycleBin', params: { teamId } })">{{ t('teamSettings.ui.recycleBin') }}</OButton>
      </div>
    </div>

    <div v-if="loading" class="team-settings__loading"><OSpinner /></div>
    <template v-else>
      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('teamSettings.ui.information') }}</h3>
          <p>{{ t('teamSettings.ui.storageUsage', { used: formatSize(team?.storageUsed || 0), quota: formatSize(team?.storageQuota || 0) }) }}</p>
        </div>
        <div class="team-quota" :class="`team-quota--${quotaState}`">
          <div class="team-quota__bar"><span :style="{ width: `${quotaPercent}%` }" /></div>
          <div class="team-quota__meta">
            <span class="font-mono">{{ quotaPercent }}%</span>
            <span v-if="quotaState === 'danger'">{{ t('teamSettings.ui.quotaDanger') }}</span>
            <span v-else-if="quotaState === 'warning'">{{ t('teamSettings.ui.quotaWarning') }}</span>
            <span v-else-if="quotaState === 'notice'">{{ t('teamSettings.ui.quotaNotice') }}</span>
            <span v-else>{{ t('teamSettings.ui.quotaHealthy') }}</span>
          </div>
        </div>
        <div class="team-section__body team-section__form">
          <OInput v-model="teamName" :label="t('nav.teamName')" :disabled="!canManage" @keyup.enter="saveTeamName" />
          <OButton v-if="canManage" variant="primary" size="sm" @click="saveTeamName">{{ t('common.save') }}</OButton>
        </div>
      </section>

      <section v-if="canManage" class="team-section">
        <div class="team-section__title">
          <h3>{{ t('teamSettings.ui.invitations') }}</h3>
          <p>{{ t('teamSettings.ui.pendingInvites', { count: invites.filter(item => item.status === 'PENDING').length }) }}</p>
        </div>
        <div class="team-members__add">
          <input v-model="inviteEmail" type="email" :placeholder="t('ui.email')" @keyup.enter="createInvite" />
          <select v-model="inviteRole">
            <option value="VIEWER">{{ t('role.viewer') }}</option>
            <option value="EDITOR">{{ t('role.editor') }}</option>
            <option v-if="isOwner" value="ADMIN">{{ t('role.admin') }}</option>
          </select>
          <OButton variant="primary" size="sm" @click="createInvite">{{ t('teamSettings.ui.sendInvite') }}</OButton>
        </div>
        <div v-if="invitesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="invites.length === 0" :title="t('teamSettings.ui.noInvites')" />
        <div v-else class="team-list">
          <div v-for="invite in invites" :key="invite.id" class="team-list__row">
            <div>
              <div class="team-members__name">{{ invite.email }}</div>
              <div class="team-members__email">{{ invite.role }} · {{ invite.status }} · {{ t('teamSettings.ui.expires', { date: formatDateFull(invite.expiresAt) }) }}</div>
            </div>
            <div class="team-members__actions">
              <OButton v-if="invite.status === 'PENDING'" variant="ghost" size="sm" @click="resendInvite(invite)">{{ t('teamSettings.ui.resend') }}</OButton>
              <OButton v-if="invite.status === 'PENDING'" variant="danger" size="sm" @click="cancelInvite(invite)">{{ t('common.cancel') }}</OButton>
            </div>
          </div>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('teamSettings.ui.activity') }}</h3>
          <p>{{ t('teamSettings.ui.activityCount', { count: activitiesTotal }) }}</p>
        </div>
        <div class="team-collection__toolbar">
          <OButton variant="ghost" size="sm" @click="loadTeamActivities">{{ t('teamSettings.ui.refresh') }}</OButton>
        </div>
        <div v-if="activitiesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="activities.length === 0" :title="t('teamSettings.ui.noActivity')" />
        <div v-else class="team-activity">
          <div v-for="activity in activities" :key="activity.id" class="team-activity__row">
            <div class="team-activity__time font-mono">{{ formatDateFull(activity.createdAt) }}</div>
            <div class="team-activity__main">
              <div class="team-activity__summary">{{ resolveMessage(activity.summaryKey, activity.summaryParams) }}</div>
              <div class="team-activity__meta">
                <span class="font-mono">{{ activity.action }}</span>
                <span v-if="activity.targetType">{{ activity.targetType }} {{ activity.targetId || '' }}</span>
                <span v-if="activity.actorUserId">{{ t('teamSettings.ui.userId', { id: activity.actorUserId }) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="activitiesTotal > TEAM_ACTIVITY_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="activitiesPage === 1" @click="goActivitiesPage(activitiesPage - 1)">{{ t('teamSettings.ui.previous') }}</OButton>
          <span class="font-mono">{{ t('teamSettings.ui.activityPage', { page: activitiesPage, pages: activitiesTotalPages, count: activitiesTotal }) }}</span>
          <OButton variant="ghost" size="sm" :disabled="activitiesPage >= activitiesTotalPages" @click="goActivitiesPage(activitiesPage + 1)">{{ t('teamSettings.ui.next') }}</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamsettings.missionCenter') }}</h3>
          <p>{{ t('team.teamsettings.auditExportZipThumbnailOnlineDocumentSavingAndCleaningTasks', { p0: tasksTotal }) }}</p>
        </div>
        <div class="team-audit__filters">
          <select v-model="taskStatus" @change="filterTeamTasks">
            <option value="">{{ t('team.teamsettings.allStatus') }}</option>
            <option value="PENDING">{{ t('team.teamsettings.pending') }}</option>
            <option value="RUNNING">{{ t('team.teamsettings.running') }}</option>
            <option value="SUCCEEDED">{{ t('commonUi.adminlogs.success') }}</option>
            <option value="FAILED">{{ t('commonUi.transfer.fail') }}</option>
          </select>
          <OInput v-model="taskType" :placeholder="t('team.teamsettings.typeSuchAsAuditExport')" @keyup.enter="filterTeamTasks" />
          <OButton variant="ghost" size="sm" @click="filterTeamTasks">{{ t('team.teamsettings.refresh') }}</OButton>
        </div>
        <div v-if="tasksLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamTasks.length === 0" :title="t('team.teamsettings.noTeamTasksYet')" />
        <div v-else class="team-tasks">
          <div v-for="task in teamTasks" :key="task.id" class="team-tasks__row">
            <div class="team-tasks__main">
              <div class="team-tasks__title">{{ resolveMessage(task.titleKey, task.titleParams) }}</div>
              <div class="team-tasks__meta">
                <span class="font-mono">{{ task.taskType }}</span>
                <span>{{ formatDateFull(task.createdAt) }}</span>
                <span v-if="task.targetType">{{ task.targetType }} {{ task.targetId || '' }}</span>
              </div>
              <div v-if="task.errorMessageKey" class="team-tasks__error">{{ resolveMessage(task.errorMessageKey, task.errorMessageParams) }}</div>
              <div v-else-if="task.resultMessageKey" class="team-tasks__result">{{ resolveMessage(task.resultMessageKey, task.resultMessageParams) }}</div>
            </div>
            <div class="team-tasks__side">
              <span class="team-tasks__status" :class="`team-tasks__status--${task.status.toLowerCase()}`">{{ task.status }}</span>
              <span class="font-mono">{{ task.progress }}%</span>
              <OButton
                v-if="task.status === 'SUCCEEDED' && ['AUDIT_EXPORT', 'FOLDER_ZIP'].includes(task.taskType) && task.resultFileName"
                variant="ghost"
                size="sm"
                @click="downloadTaskResult(task)"
              >{{ t('commonUi.filepreview.download') }}</OButton>
              <OButton v-if="task.status === 'FAILED'" variant="ghost" size="sm" @click="retryTask(task)">{{ t('team.upload.tryAgain') }}</OButton>
            </div>
          </div>
        </div>
        <div v-if="tasksTotal > TEAM_TASK_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="tasksPage === 1" @click="goTaskPage(tasksPage - 1)">{{ t('commonUi.adminlogs.previousPage') }}</OButton>
          <span class="font-mono">{{ t('team.teamsettings.pageTotalTasks', { p0: tasksPage, p1: tasksTotalPages, p2: tasksTotal }) }}</span>
          <OButton variant="ghost" size="sm" :disabled="tasksPage >= tasksTotalPages" @click="goTaskPage(tasksPage + 1)">{{ t('commonUi.adminlogs.nextPage') }}</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('commonUi.adminteams.member') }}</h3>
          <p>{{ t('team.teamsettings.members', { p0: members.length }) }}</p>
        </div>
        <div v-if="canManage" class="team-members__add">
          <input v-model="addUsername" :placeholder="t('commonUi.desktopsettings.username')" @keyup.enter="addMember" />
          <select v-model="addRole">
            <option value="VIEWER">{{ t('role.viewer') }}</option>
            <option value="EDITOR">{{ t('role.editor') }}</option>
            <option v-if="isOwner" value="ADMIN">{{ t('role.admin') }}</option>
          </select>
          <OButton variant="primary" size="sm" @click="addMember">{{ t('team.teamfiles.addTo') }}</OButton>
        </div>
        <div v-if="membersLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <div v-else class="team-members__list">
          <div v-for="member in members" :key="member.userId" class="team-members__row">
            <div>
              <div class="team-members__name">{{ member.username }}</div>
              <div class="team-members__email">{{ member.email || '-' }}</div>
            </div>
            <select
              :value="member.role"
              :disabled="!canManage || member.role === 'OWNER'"
              @change="event => updateMemberRole(member, (event.target as HTMLSelectElement).value)"
            >
              <option value="OWNER" disabled>{{ t('role.owner') }}</option>
              <option v-if="isOwner || member.role === 'ADMIN'" value="ADMIN">{{ t('role.admin') }}</option>
              <option value="EDITOR">{{ t('role.editor') }}</option>
              <option value="VIEWER">{{ t('role.viewer') }}</option>
            </select>
            <div class="team-members__actions">
              <OButton v-if="isOwner && member.role !== 'OWNER'" variant="ghost" size="sm" @click="transferOwner(member)">{{ t('team.teamsettings.transfer') }}</OButton>
              <OButton v-if="canManage && member.role !== 'OWNER'" variant="danger" size="sm" @click="removeMember(member)">{{ t('commonUi.tags.remove') }}</OButton>
            </div>
          </div>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamfiles.teamTag') }}</h3>
          <p>{{ t('team.teamsettings.teamTagsAreVisibleToAllMembersAndEditorsCan') }}</p>
        </div>
        <div v-if="canManage" class="team-tags__add">
          <input v-model="newTagName" :placeholder="t('team.tags.tagName')" @keyup.enter="createTeamTag" />
          <input v-model="newTagColor" type="color" :aria-label="t('team.teamsettings.labelColor')" />
          <OButton variant="primary" size="sm" @click="createTeamTag">{{ t('team.teamsettings.new') }}</OButton>
        </div>
        <div v-if="tagsLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamTags.length === 0" :title="t('team.teamsettings.noTeamTagYet')" />
        <div v-else class="team-tags__list">
          <div v-for="tag in teamTags" :key="tag.id" class="team-tags__item">
            <span class="team-tags__swatch" :style="{ background: tag.color || '#00d4aa' }" />
            <span>{{ tag.tagName }}</span>
            <OButton class="team-tags__view" variant="ghost" size="sm" @click="openTagFiles(tag)">{{ t('team.teamsettings.viewFiles') }}</OButton>
            <OButton v-if="canManage" variant="danger" size="sm" @click="deleteTeamTag(tag)">{{ t('commonUi.adminuserfiles.delete') }}</OButton>
          </div>
        </div>
      </section>

      <section v-if="selectedTag" class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamsettings.tagFile', { p0: selectedTag.tagName }) }}</h3>
          <p>{{ t('team.teamsettings.viewFilesTaggedWithThisTeamTag') }}</p>
        </div>
        <div class="team-collection__toolbar">
          <OInput v-model="tagFilesQuery" :placeholder="t('team.teamsettings.searchForFilesUnderThisTag')" @keyup.enter="searchTagFiles" />
          <OButton variant="ghost" size="sm" @click="searchTagFiles">{{ t('commonUi.adminteams.search') }}</OButton>
          <OButton variant="subtle" size="sm" @click="selectedTag = null">{{ t('commonUi.o.closure') }}</OButton>
        </div>
        <div v-if="tagFilesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="tagFiles.length === 0" :title="t('team.teamsettings.noTagFilesYet')" />
        <div v-else class="team-collection">
          <div v-for="file in tagFiles" :key="file.id" class="team-collection__row">
            <div>
              <div class="team-collection__name">{{ file.fileName }}</div>
              <div class="team-collection__meta">{{ t('team.teamsettings.value', { p0: t('team.teamsettings.value', { p0: t('team.teamsettings.parameterPair', { p0: file.fileType === 'folder' ? t('commonUi.desktopfolderupload.folder') : formatSize(file.fileSize), p1: formatDateFull(file.updatedAt) }) }) }) }}</div>
            </div>
            <OButton variant="ghost" size="sm" @click="openTeamFileLocation(file)">{{ t('team.teamsettings.openLocation') }}</OButton>
          </div>
        </div>
        <div v-if="tagFilesTotal > TEAM_COLLECTION_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="tagFilesPage === 1" @click="goTagFilesPage(tagFilesPage - 1)">{{ t('commonUi.adminlogs.previousPage') }}</OButton>
          <span class="font-mono">{{ t('team.teamsettings.pageTotalFiles', { p0: tagFilesPage, p1: tagFilesTotalPages, p2: tagFilesTotal }) }}</span>
          <OButton variant="ghost" size="sm" :disabled="tagFilesPage >= tagFilesTotalPages" @click="goTagFilesPage(tagFilesPage + 1)">{{ t('commonUi.adminlogs.nextPage') }}</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamsettings.teamCollection') }}</h3>
          <p>{{ t('team.teamsettings.teamFilesCollectedByMembersAreDisplayedAccordingToThe') }}</p>
        </div>
        <div class="team-collection__toolbar">
          <OInput v-model="favoritesQuery" :placeholder="t('team.teamsettings.searchFavoritedTeamFiles')" @keyup.enter="searchFavorites" />
          <OButton variant="ghost" size="sm" @click="searchFavorites">{{ t('commonUi.adminteams.search') }}</OButton>
        </div>
        <div v-if="favoritesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamFavorites.length === 0" :title="t('team.teamsettings.noTeamFavoritesYet')" :description="t('team.teamsettings.rightClickTheFileInTheTeamFileToAdd')" />
        <div v-else class="team-collection">
          <div v-for="file in teamFavorites" :key="file.id" class="team-collection__row">
            <div>
              <div class="team-collection__name">{{ file.fileName }}</div>
              <div class="team-collection__meta">{{ t('team.teamsettings.value', { p0: t('team.teamsettings.value', { p0: t('team.teamsettings.parameterPair', { p0: file.fileType === 'folder' ? t('commonUi.desktopfolderupload.folder') : formatSize(file.fileSize), p1: formatDateFull(file.updatedAt) }) }) }) }}</div>
            </div>
            <OButton variant="ghost" size="sm" @click="openTeamFileLocation(file)">{{ t('team.teamsettings.openLocation') }}</OButton>
          </div>
        </div>
        <div v-if="favoritesTotal > TEAM_COLLECTION_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="favoritesPage === 1" @click="goFavoritesPage(favoritesPage - 1)">{{ t('commonUi.adminlogs.previousPage') }}</OButton>
          <span class="font-mono">{{ t('team.teamsettings.pageTotalFiles', { p0: favoritesPage, p1: favoritesTotalPages, p2: favoritesTotal }) }}</span>
          <OButton variant="ghost" size="sm" :disabled="favoritesPage >= favoritesTotalPages" @click="goFavoritesPage(favoritesPage + 1)">{{ t('commonUi.adminlogs.nextPage') }}</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamsettings.teamSharing') }}</h3>
          <p>{{ t('team.teamsettings.sharedLinks', { p0: shares.length }) }}</p>
        </div>
        <div v-if="sharesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="shares.length === 0" :title="t('team.teamsettings.noTeamSharingYet')" :description="t('team.teamsettings.rightClickTheFileInTheTeamFileToCreate')" />
        <div v-else class="team-shares__list">
          <div v-for="share in shares" :key="share.id" class="team-shares__row">
            <div class="team-shares__main">
              <div class="team-shares__url font-mono truncate">{{ buildShareUrl(share.shareCode) }}</div>
              <div class="team-shares__meta">
                <span v-if="share.expireAt" :style="isExpired(share) ? 'color:var(--danger)' : 'color:var(--text-secondary)'">{{ t('team.teamsettings.value', { p0: t('team.teamsettings.value', { p0: t('team.teamsettings.value', { p0: isExpired(share) ? t('commonUi.searchoverlay.expiredStatus') : t('commonUi.searchoverlay.expired', { p0: formatDateFull(share.expireAt) }) }) }) }) }}</span>
                <span v-else>{{ t('commonUi.searchoverlay.neverExpires') }}</span>
                <span>{{ t('team.teamsettings.visit', { p0: share.visitCount, p1: share.maxVisits ? `/${share.maxVisits}` : '' }) }}</span>
                <span>{{ t('team.transfer.download', { p0: share.downloadCount }) }}</span>
                <span v-if="share.hasPassword">{{ t('commonUi.searchoverlay.havePassword') }}</span>
              </div>
            </div>
            <div class="team-shares__actions">
              <OButton variant="subtle" size="sm" @click="openDetail(share)">{{ t('commonUi.adminlogs.details') }}</OButton>
              <OButton
                v-if="share.hasPassword && canManageShare(share)"
                variant="ghost"
                size="sm"
                icon
                :loading="loadingPasswordShareId === share.id"
                :aria-label="t('team.shares.viewPassword')"
                @click="loadSharePassword(share)"
              >
                <Eye :size="14" />
              </OButton>
              <OButton v-if="canManageShare(share)" variant="ghost" size="sm" @click="openEdit(share)">{{ t('commonUi.shares.edit') }}</OButton>
              <OButton variant="ghost" size="sm" @click="copyLink(share)">{{ t('team.searchoverlay.copy') }}</OButton>
              <OButton v-if="canManageShare(share)" variant="danger" size="sm" @click="deleteShare(share)">{{ t('commonUi.oconfirmdialog.cancel') }}</OButton>
            </div>
          </div>
        </div>
      </section>

      <section v-if="canManage" class="team-section">
        <div class="team-section__title">
          <h3>{{ t('adminUi.adminlogs.auditLog') }}</h3>
          <p>{{ t('team.teamsettings.recordTeamMembersFilesSharesVersionsTagsAndQuotaOperations', { p0: auditTotal }) }}</p>
        </div>
        <div class="team-audit__filters">
          <OInput v-model="auditAction" :placeholder="t('team.teamsettings.actionSuchAsFileDelete')" @keyup.enter="filterAuditLogs" />
          <OInput v-model="auditTargetType" :placeholder="t('adminUi.adminteams.targetSuchAsFile')" @keyup.enter="filterAuditLogs" />
          <select v-model="auditResult" @change="filterAuditLogs">
            <option value="">{{ t('commonUi.adminlogs.allResults') }}</option>
            <option value="1">{{ t('commonUi.adminlogs.success') }}</option>
            <option value="0">{{ t('commonUi.transfer.fail') }}</option>
          </select>
          <OButton variant="ghost" size="sm" @click="filterAuditLogs">{{ t('commonUi.adminlogs.filter') }}</OButton>
          <OButton variant="ghost" size="sm" @click="exportAuditLogs">{{ t('adminUi.adminteams.exportCsv') }}</OButton>
        </div>
        <div v-if="auditLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="auditLogs.length === 0" :title="t('commonUi.adminlogs.noAuditLogYet')" />
        <div v-else class="team-audit">
          <div v-for="log in auditLogs" :key="log.id" class="team-audit__row">
            <div class="team-audit__time font-mono">{{ formatDateFull(log.createdAt) }}</div>
            <div class="team-audit__action font-mono">{{ log.action }}</div>
            <div class="team-audit__target">{{ log.targetType || '-' }} {{ log.targetId || '' }}</div>
            <div class="team-audit__details">{{ log.details || '-' }}</div>
          </div>
        </div>
        <div v-if="auditTotal > TEAM_AUDIT_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="auditPage === 1" @click="goAuditPage(auditPage - 1)">{{ t('commonUi.adminlogs.previousPage') }}</OButton>
          <span class="font-mono">{{ t('team.teamsettings.pageTotalLogs', { p0: auditPage, p1: auditTotalPages, p2: auditTotal }) }}</span>
          <OButton variant="ghost" size="sm" :disabled="auditPage >= auditTotalPages" @click="goAuditPage(auditPage + 1)">{{ t('commonUi.adminlogs.nextPage') }}</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>{{ t('team.teamsettings.dangerousOperation') }}</h3>
          <p>{{ t('team.teamsettings.pleaseConfirmTheOwnershipOfTheTeamFilesBeforeQuitting') }}</p>
        </div>
        <div class="team-settings__danger">
          <OButton v-if="!isOwner" variant="danger" size="sm" @click="leaveTeam">{{ t('team.teamsettings.quitTheTeam') }}</OButton>
          <OButton v-if="isOwner" variant="danger" size="sm" @click="deleteTeam">{{ t('team.teamsettings.deleteTeam') }}</OButton>
        </div>
      </section>
    </template>

    <OModal :title="t('team.shares.shareDetails')" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailShare" class="share-info">
        <div><span>{{ t('team.shares.link') }}</span><strong class="font-mono">{{ buildShareUrl(detailShare.shareCode) }}</strong></div>
        <div><span>{{ t('team.shares.fileId') }}</span><strong class="font-mono">{{ detailShare.fileId }}</strong></div>
        <div><span>{{ t('team.shares.access') }}</span><strong class="font-mono">{{ detailShare.visitCount }}{{ detailShare.maxVisits ? `/${detailShare.maxVisits}` : '' }}</strong></div>
        <div><span>{{ t('commonUi.filepreview.download') }}</span><strong class="font-mono">{{ detailShare.downloadCount }}</strong></div>
        <div><span>{{ t('commonUi.desktopsettings.password') }}</span><strong>{{ detailShare.hasPassword ? (detailPassword || t('commonUi.searchoverlay.havePassword')) : t('commonUi.searchoverlay.noPassword') }}</strong></div>
        <div><span>{{ t('team.shares.expired') }}</span><strong>{{ detailShare.expireAt ? formatDateFull(detailShare.expireAt) : t('commonUi.searchoverlay.neverExpires') }}</strong></div>
        <div><span>{{ t('commonUi.adminlogs.state') }}</span><strong>{{ detailShare.isCanceled ? t('commonUi.transfer.canceled') : isExpired(detailShare) ? t('commonUi.searchoverlay.expiredStatus') : t('team.shares.efficient') }}</strong></div>
      </div>
    </OModal>

    <OModal :title="t('team.teamsettings.sharedByTheEditorialTeam')" :open="editOpen" @close="editOpen = false">
      <div class="share-edit">
        <div class="share-edit__field">
          <OInput v-model="editMaxVisits" :label="t('commonUi.filebrowser.visitLimit')" type="number" :placeholder="t('commonUi.filebrowser.leaveBlankToIndicateNoLimit')" />
          <OButton variant="ghost" size="sm" @click="editMaxVisits = ''">{{ t('team.shares.clearVisits') }}</OButton>
        </div>
        <div class="share-edit__field">
          <OInput v-model="editExpireAt" :label="t('team.shares.expirationTime')" type="datetime-local" />
          <OButton variant="ghost" size="sm" @click="editExpireAt = ''">{{ t('team.shares.clearExpirationTime') }}</OButton>
        </div>
        <OInput v-model="editPassword" :label="t('team.shares.newAccessPassword')" type="text" :placeholder="t('team.teamsettings.leaveBlankToIndicateNoChanges')" />
        <label class="share-edit__check">
          <input v-model="editClearPassword" type="checkbox" />
          <span>{{ t('team.shares.removeAccessCode') }}</span>
        </label>
        <p>{{ t('team.shares.theNumberOfClearAccessesAndExpirationTimeWillBe') }}</p>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="editOpen = false">{{ t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" @click="submitEdit">{{ t('commonUi.desktopsettings.save') }}</OButton>
      </template>
    </OModal>
  </div>
</template>

<style scoped>
.team-settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.team-settings__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.team-settings__header h2,
.team-section__title h3 {
  margin: 0;
}
.team-settings__header p,
.team-section__title p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.team-settings__header-actions,
.team-settings__danger,
.team-shares__actions,
.team-members__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.team-settings__loading,
.team-settings__inline-loading {
  display: flex;
  justify-content: center;
  padding: 28px;
}
.team-section {
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.team-section__title {
  margin-bottom: 12px;
}
.team-section__actions {
  display: flex;
  justify-content: flex-end;
  margin: -4px 0 10px;
}
.team-section__body,
.team-section__form {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}
.team-section__form :deep(.o-input) {
  min-width: min(360px, 100%);
}
.team-quota {
  margin-bottom: 12px;
}
.team-quota__bar {
  height: 8px;
  border-radius: 999px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  overflow: hidden;
}
.team-quota__bar span {
  display: block;
  height: 100%;
  background: var(--accent);
}
.team-quota--notice .team-quota__bar span { background: #f59e0b; }
.team-quota--warning .team-quota__bar span,
.team-quota--danger .team-quota__bar span { background: var(--danger); }
.team-quota__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  color: var(--text-secondary);
  font-size: 12px;
}
.team-members__add {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px auto;
  gap: 8px;
  margin-bottom: 10px;
}
.team-members__add input,
.team-members__add select,
.team-members__row select {
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
  font-family: var(--font-sans);
}
.team-members__list,
.team-shares__list,
.team-list,
.team-tags__list,
.team-audit,
.team-activity {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.team-members__row,
.team-shares__row,
.team-list__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px auto;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.team-shares__row {
  grid-template-columns: minmax(0, 1fr) auto;
}
.team-list__row {
  grid-template-columns: minmax(0, 1fr) auto;
}
.team-members__name {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
}
.team-members__email,
.team-shares__meta,
.share-edit p,
.share-edit__check {
  color: var(--text-secondary);
  font-size: 12px;
}
.team-shares__main {
  min-width: 0;
}
.team-shares__url {
  color: var(--accent);
  font-size: 12px;
  margin-bottom: 4px;
}
.team-shares__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.team-tags__add {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 48px auto;
  gap: 8px;
  margin-bottom: 10px;
  max-width: 520px;
}
.team-tags__add input {
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
}
.team-tags__add input[type="color"] {
  padding: 3px;
}
.team-tags__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  font-size: 13px;
}
.team-tags__view {
  margin-left: auto;
}
.team-tags__swatch {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid var(--border);
}
.team-collection__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.team-collection__toolbar :deep(.o-input) {
  min-width: min(320px, 100%);
}
.team-collection {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.team-collection__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.team-collection__name {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.team-collection__meta,
.team-collection__pagination {
  color: var(--text-secondary);
  font-size: 12px;
}
.team-collection__pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.team-audit__row {
  display: grid;
  grid-template-columns: 170px 150px 120px minmax(0, 1fr);
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
  font-size: 12px;
}
.team-audit__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: -4px 0 10px;
}
.team-audit__filters :deep(.o-input) {
  width: 220px;
}
.team-audit__filters select {
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
}
.team-audit__time,
.team-audit__target,
.team-audit__details {
  color: var(--text-secondary);
  min-width: 0;
  overflow-wrap: anywhere;
}
.team-audit__action {
  color: var(--accent);
}
.team-activity__row {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr);
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.team-activity__time,
.team-activity__meta {
  color: var(--text-secondary);
  font-size: 12px;
}
.team-activity__main {
  min-width: 0;
}
.team-activity__summary {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.team-activity__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}
.team-tasks {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.team-tasks__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 10px;
  align-items: center;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.team-tasks__main {
  min-width: 0;
}
.team-tasks__title {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}
.team-tasks__meta,
.team-tasks__result,
.team-tasks__error,
.team-tasks__side {
  color: var(--text-secondary);
  font-size: 12px;
}
.team-tasks__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}
.team-tasks__error {
  margin-top: 5px;
  color: var(--danger);
  overflow-wrap: anywhere;
}
.team-tasks__result {
  margin-top: 5px;
  color: var(--accent);
  overflow-wrap: anywhere;
}
.team-tasks__side {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.team-tasks__status {
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 8px;
  font-size: 11px;
}
.team-tasks__status--succeeded {
  color: var(--accent);
  border-color: rgba(0, 212, 170, 0.35);
  background: var(--accent-dim);
}
.team-tasks__status--failed {
  color: var(--danger);
}
.team-tasks__status--running {
  color: #f59e0b;
}
.share-info,
.share-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.share-edit__field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: end;
}
.share-edit__check {
  display: flex;
  align-items: center;
  flex-direction: row;
}
.share-info div {
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 10px;
  font-size: 13px;
}
.share-info span {
  color: var(--text-secondary);
}
.share-info strong {
  font-weight: 500;
  overflow-wrap: anywhere;
}
@media (max-width: 720px) {
  .team-members__add,
  .team-members__row,
  .team-shares__row,
  .team-list__row,
  .team-tags__add,
  .team-tasks__row,
  .team-activity__row,
  .team-audit__row {
    grid-template-columns: 1fr;
  }
  .team-settings__header {
    flex-direction: column;
  }
}
</style>
