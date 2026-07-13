<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye } from 'lucide-vue-next'
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
import { resolveMessage } from '@/i18n'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const teamsStore = useTeamsStore()
const { confirm } = useConfirm()
const { formatDateFull, formatSize } = useFormatters()

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
    const { data } = await teamsApi.listMembers(teamId.value)
    members.value = data.data
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
    const { data } = await teamsApi.listInvites(teamId.value)
    invites.value = data.data
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
  toast.success('审计导出任务已提交')
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
  toast.success('任务已重新排队')
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
  toast.success('团队名称已更新')
}

async function addMember() {
  if (!canManage.value || !addUsername.value.trim()) return
  await teamsApi.addMember(teamId.value, addUsername.value.trim(), addRole.value)
  addUsername.value = ''
  addRole.value = 'VIEWER'
  toast.success('成员已添加')
  await loadMembers()
}

async function createInvite() {
  if (!canManage.value || !inviteEmail.value.trim()) return
  await teamsApi.createInvite(teamId.value, inviteEmail.value.trim(), inviteRole.value)
  inviteEmail.value = ''
  inviteRole.value = 'VIEWER'
  toast.success('邀请已发送')
  await loadInvites()
}

async function resendInvite(invite: TeamInvite) {
  await teamsApi.resendInvite(teamId.value, invite.id)
  toast.success('邀请已重发')
  await loadInvites()
}

async function cancelInvite(invite: TeamInvite) {
  const ok = await confirm('取消邀请', `取消发送给 ${invite.email} 的团队邀请？`)
  if (!ok) return
  await teamsApi.cancelInvite(teamId.value, invite.id)
  toast.success('邀请已取消')
  await loadInvites()
}

async function createTeamTag() {
  if (!canManage.value || !newTagName.value.trim()) return
  await teamsApi.createTeamTag(teamId.value, newTagName.value.trim(), newTagColor.value)
  newTagName.value = ''
  toast.success('团队标签已创建')
  await loadTeamTags()
}

async function deleteTeamTag(tag: TagEntity) {
  if (!canManage.value) return
  const ok = await confirm('删除团队标签', `确认删除「${tag.tagName}」？`)
  if (!ok) return
  await teamsApi.deleteTeamTag(teamId.value, tag.id)
  toast.success('团队标签已删除')
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
  toast.success('角色已更新')
  await loadMembers()
}

async function removeMember(member: TeamMember) {
  if (!canManage.value || member.role === 'OWNER') return
  const ok = await confirm('移除成员', `确认移除「${member.username}」？`)
  if (!ok) return
  await teamsApi.removeMember(teamId.value, member.userId)
  toast.success('成员已移除')
  await loadMembers()
}

async function transferOwner(member: TeamMember) {
  if (!isOwner.value || member.role === 'OWNER') return
  const ok = await confirm('转让团队', `将「${member.username}」设为团队所有者？转让后你会变为管理员。`)
  if (!ok) return
  const { data } = await teamsApi.transferTeamOwner(teamId.value, member.userId)
  team.value = data.data
  teamsStore.upsertTeam(data.data)
  toast.success('团队所有者已转让')
  await Promise.all([loadMembers(), loadShares(), loadInvites(), loadAuditLogs()])
}

async function leaveTeam() {
  if (isOwner.value) return
  const ok = await confirm('退出团队', '退出后将无法访问该团队文件。')
  if (!ok) return
  await teamsApi.leaveTeam(teamId.value)
  teamsStore.removeTeam(teamId.value)
  toast.success('已退出团队')
  await router.push({ name: 'Files' })
}

async function deleteTeam() {
  if (!isOwner.value || !team.value) return
  const ok = await confirm('删除团队', `确认删除「${team.value.name}」？团队文件也会被删除。`)
  if (!ok) return
  await teamsApi.deleteTeam(teamId.value)
  teamsStore.removeTeam(teamId.value)
  toast.success('团队已删除')
  await router.push({ name: 'Files' })
}

function canManageShare(share: ShareLinkEntity) {
  return canManage.value || share.userId === auth.user?.id
}

function isExpired(share: ShareLinkEntity) {
  return share.expireAt && new Date(share.expireAt) < new Date()
}

async function copyLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(buildShareUrl(share.shareCode)).catch(() => {})
  toast.success('链接已复制')
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
    toast.error('访问次数限制必须是正整数')
    return
  }
  const password = editPassword.value.trim()
  if (!editClearPassword.value && password && (password.length < 4 || password.length > 16)) {
    toast.error('分享密码长度必须为 4-16 位')
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
  toast.success('分享已更新')
  await loadShares()
}

async function deleteShare(share: ShareLinkEntity) {
  if (!canManageShare(share)) return
  const ok = await confirm('取消团队分享', '取消后分享链接将立即失效。')
  if (!ok) return
  await teamsApi.deleteTeamShare(teamId.value, share.id)
  toast.success('分享已取消')
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
        <h2>团队设置</h2>
        <p class="text-muted">{{ team?.name || '团队' }} · {{ team?.role || '' }}</p>
      </div>
      <div class="team-settings__header-actions">
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamFiles', params: { teamId } })">返回文件</OButton>
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamRecycleBin', params: { teamId } })">团队回收站</OButton>
      </div>
    </div>

    <div v-if="loading" class="team-settings__loading"><OSpinner /></div>
    <template v-else>
      <section class="team-section">
        <div class="team-section__title">
          <h3>团队信息</h3>
          <p>存储 {{ formatSize(team?.storageUsed || 0) }} / {{ formatSize(team?.storageQuota || 0) }} · 配额由系统管理员调整</p>
        </div>
        <div class="team-quota" :class="`team-quota--${quotaState}`">
          <div class="team-quota__bar"><span :style="{ width: `${quotaPercent}%` }" /></div>
          <div class="team-quota__meta">
            <span class="font-mono">{{ quotaPercent }}%</span>
            <span v-if="quotaState === 'danger'">团队空间已用尽，上传和文档保存可能失败</span>
            <span v-else-if="quotaState === 'warning'">团队空间接近上限</span>
            <span v-else-if="quotaState === 'notice'">团队空间已超过 80%</span>
            <span v-else>团队空间充足</span>
          </div>
        </div>
        <div class="team-section__body team-section__form">
          <OInput v-model="teamName" label="团队名称" :disabled="!canManage" @keyup.enter="saveTeamName" />
          <OButton v-if="canManage" variant="primary" size="sm" @click="saveTeamName">保存</OButton>
        </div>
      </section>

      <section v-if="canManage" class="team-section">
        <div class="team-section__title">
          <h3>邀请</h3>
          <p>{{ invites.filter(item => item.status === 'PENDING').length }} 个待处理邀请</p>
        </div>
        <div class="team-members__add">
          <input v-model="inviteEmail" type="email" placeholder="邮箱地址" @keyup.enter="createInvite" />
          <select v-model="inviteRole">
            <option value="VIEWER">Viewer</option>
            <option value="EDITOR">Editor</option>
            <option v-if="isOwner" value="ADMIN">Admin</option>
          </select>
          <OButton variant="primary" size="sm" @click="createInvite">发送邀请</OButton>
        </div>
        <div v-if="invitesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="invites.length === 0" title="暂无邀请" />
        <div v-else class="team-list">
          <div v-for="invite in invites" :key="invite.id" class="team-list__row">
            <div>
              <div class="team-members__name">{{ invite.email }}</div>
              <div class="team-members__email">{{ invite.role }} · {{ invite.status }} · 过期 {{ formatDateFull(invite.expiresAt) }}</div>
            </div>
            <div class="team-members__actions">
              <OButton v-if="invite.status === 'PENDING'" variant="ghost" size="sm" @click="resendInvite(invite)">重发</OButton>
              <OButton v-if="invite.status === 'PENDING'" variant="danger" size="sm" @click="cancelInvite(invite)">取消</OButton>
            </div>
          </div>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>活动流</h3>
          <p>成员可见的团队活动 · 共 {{ activitiesTotal }} 条</p>
        </div>
        <div class="team-collection__toolbar">
          <OButton variant="ghost" size="sm" @click="loadTeamActivities">刷新</OButton>
        </div>
        <div v-if="activitiesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="activities.length === 0" title="暂无团队活动" />
        <div v-else class="team-activity">
          <div v-for="activity in activities" :key="activity.id" class="team-activity__row">
            <div class="team-activity__time font-mono">{{ formatDateFull(activity.createdAt) }}</div>
            <div class="team-activity__main">
              <div class="team-activity__summary">{{ resolveMessage(activity.summaryKey, activity.summaryParams) }}</div>
              <div class="team-activity__meta">
                <span class="font-mono">{{ activity.action }}</span>
                <span v-if="activity.targetType">{{ activity.targetType }} {{ activity.targetId || '' }}</span>
                <span v-if="activity.actorUserId">用户 {{ activity.actorUserId }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="activitiesTotal > TEAM_ACTIVITY_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="activitiesPage === 1" @click="goActivitiesPage(activitiesPage - 1)">上一页</OButton>
          <span class="font-mono">第 {{ activitiesPage }} / {{ activitiesTotalPages }} 页 · 共 {{ activitiesTotal }} 条活动</span>
          <OButton variant="ghost" size="sm" :disabled="activitiesPage >= activitiesTotalPages" @click="goActivitiesPage(activitiesPage + 1)">下一页</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>任务中心</h3>
          <p>审计导出、ZIP、缩略图、在线文档保存和清理任务 · 共 {{ tasksTotal }} 条</p>
        </div>
        <div class="team-audit__filters">
          <select v-model="taskStatus" @change="filterTeamTasks">
            <option value="">全部状态</option>
            <option value="PENDING">待处理</option>
            <option value="RUNNING">运行中</option>
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
          </select>
          <OInput v-model="taskType" placeholder="类型，如 AUDIT_EXPORT" @keyup.enter="filterTeamTasks" />
          <OButton variant="ghost" size="sm" @click="filterTeamTasks">刷新</OButton>
        </div>
        <div v-if="tasksLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamTasks.length === 0" title="暂无团队任务" />
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
              >
                下载
              </OButton>
              <OButton v-if="task.status === 'FAILED'" variant="ghost" size="sm" @click="retryTask(task)">重试</OButton>
            </div>
          </div>
        </div>
        <div v-if="tasksTotal > TEAM_TASK_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="tasksPage === 1" @click="goTaskPage(tasksPage - 1)">上一页</OButton>
          <span class="font-mono">第 {{ tasksPage }} / {{ tasksTotalPages }} 页 · 共 {{ tasksTotal }} 条任务</span>
          <OButton variant="ghost" size="sm" :disabled="tasksPage >= tasksTotalPages" @click="goTaskPage(tasksPage + 1)">下一页</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>成员</h3>
          <p>{{ members.length }} 个成员</p>
        </div>
        <div v-if="canManage" class="team-members__add">
          <input v-model="addUsername" placeholder="用户名" @keyup.enter="addMember" />
          <select v-model="addRole">
            <option value="VIEWER">Viewer</option>
            <option value="EDITOR">Editor</option>
            <option v-if="isOwner" value="ADMIN">Admin</option>
          </select>
          <OButton variant="primary" size="sm" @click="addMember">添加</OButton>
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
              <option value="OWNER" disabled>Owner</option>
              <option v-if="isOwner || member.role === 'ADMIN'" value="ADMIN">Admin</option>
              <option value="EDITOR">Editor</option>
              <option value="VIEWER">Viewer</option>
            </select>
            <div class="team-members__actions">
              <OButton v-if="isOwner && member.role !== 'OWNER'" variant="ghost" size="sm" @click="transferOwner(member)">转让</OButton>
              <OButton v-if="canManage && member.role !== 'OWNER'" variant="danger" size="sm" @click="removeMember(member)">移除</OButton>
            </div>
          </div>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>团队标签</h3>
          <p>团队标签对全体成员可见，编辑者可给文件打标签</p>
        </div>
        <div v-if="canManage" class="team-tags__add">
          <input v-model="newTagName" placeholder="标签名称" @keyup.enter="createTeamTag" />
          <input v-model="newTagColor" type="color" aria-label="标签颜色" />
          <OButton variant="primary" size="sm" @click="createTeamTag">新建</OButton>
        </div>
        <div v-if="tagsLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamTags.length === 0" title="暂无团队标签" />
        <div v-else class="team-tags__list">
          <div v-for="tag in teamTags" :key="tag.id" class="team-tags__item">
            <span class="team-tags__swatch" :style="{ background: tag.color || '#00d4aa' }" />
            <span>{{ tag.tagName }}</span>
            <OButton class="team-tags__view" variant="ghost" size="sm" @click="openTagFiles(tag)">查看文件</OButton>
            <OButton v-if="canManage" variant="danger" size="sm" @click="deleteTeamTag(tag)">删除</OButton>
          </div>
        </div>
      </section>

      <section v-if="selectedTag" class="team-section">
        <div class="team-section__title">
          <h3>标签文件：{{ selectedTag.tagName }}</h3>
          <p>查看已标记为该团队标签的文件</p>
        </div>
        <div class="team-collection__toolbar">
          <OInput v-model="tagFilesQuery" placeholder="搜索此标签下的文件..." @keyup.enter="searchTagFiles" />
          <OButton variant="ghost" size="sm" @click="searchTagFiles">搜索</OButton>
          <OButton variant="subtle" size="sm" @click="selectedTag = null">关闭</OButton>
        </div>
        <div v-if="tagFilesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="tagFiles.length === 0" title="暂无标签文件" />
        <div v-else class="team-collection">
          <div v-for="file in tagFiles" :key="file.id" class="team-collection__row">
            <div>
              <div class="team-collection__name">{{ file.fileName }}</div>
              <div class="team-collection__meta">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }} · {{ formatDateFull(file.updatedAt) }}</div>
            </div>
            <OButton variant="ghost" size="sm" @click="openTeamFileLocation(file)">
              打开位置
            </OButton>
          </div>
        </div>
        <div v-if="tagFilesTotal > TEAM_COLLECTION_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="tagFilesPage === 1" @click="goTagFilesPage(tagFilesPage - 1)">上一页</OButton>
          <span class="font-mono">第 {{ tagFilesPage }} / {{ tagFilesTotalPages }} 页 · 共 {{ tagFilesTotal }} 个文件</span>
          <OButton variant="ghost" size="sm" :disabled="tagFilesPage >= tagFilesTotalPages" @click="goTagFilesPage(tagFilesPage + 1)">下一页</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>团队收藏</h3>
          <p>成员收藏的团队文件，按当前登录用户的收藏关系展示</p>
        </div>
        <div class="team-collection__toolbar">
          <OInput v-model="favoritesQuery" placeholder="搜索收藏的团队文件..." @keyup.enter="searchFavorites" />
          <OButton variant="ghost" size="sm" @click="searchFavorites">搜索</OButton>
        </div>
        <div v-if="favoritesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="teamFavorites.length === 0" title="暂无团队收藏" description="在团队文件中右键文件可加入收藏" />
        <div v-else class="team-collection">
          <div v-for="file in teamFavorites" :key="file.id" class="team-collection__row">
            <div>
              <div class="team-collection__name">{{ file.fileName }}</div>
              <div class="team-collection__meta">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }} · {{ formatDateFull(file.updatedAt) }}</div>
            </div>
            <OButton variant="ghost" size="sm" @click="openTeamFileLocation(file)">
              打开位置
            </OButton>
          </div>
        </div>
        <div v-if="favoritesTotal > TEAM_COLLECTION_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="favoritesPage === 1" @click="goFavoritesPage(favoritesPage - 1)">上一页</OButton>
          <span class="font-mono">第 {{ favoritesPage }} / {{ favoritesTotalPages }} 页 · 共 {{ favoritesTotal }} 个文件</span>
          <OButton variant="ghost" size="sm" :disabled="favoritesPage >= favoritesTotalPages" @click="goFavoritesPage(favoritesPage + 1)">下一页</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>团队分享</h3>
          <p>{{ shares.length }} 个分享链接</p>
        </div>
        <div v-if="sharesLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="shares.length === 0" title="暂无团队分享" description="在团队文件中右键文件可创建分享链接" />
        <div v-else class="team-shares__list">
          <div v-for="share in shares" :key="share.id" class="team-shares__row">
            <div class="team-shares__main">
              <div class="team-shares__url font-mono truncate">{{ buildShareUrl(share.shareCode) }}</div>
              <div class="team-shares__meta">
                <span v-if="share.expireAt" :style="isExpired(share) ? 'color:var(--danger)' : 'color:var(--text-secondary)'">
                  {{ isExpired(share) ? '已过期' : `过期：${formatDateFull(share.expireAt)}` }}
                </span>
                <span v-else>永不过期</span>
                <span>访问 {{ share.visitCount }}{{ share.maxVisits ? `/${share.maxVisits}` : '' }}</span>
                <span>下载 {{ share.downloadCount }}</span>
                <span v-if="share.hasPassword">有密码</span>
              </div>
            </div>
            <div class="team-shares__actions">
              <OButton variant="subtle" size="sm" @click="openDetail(share)">详情</OButton>
              <OButton
                v-if="share.hasPassword && canManageShare(share)"
                variant="ghost"
                size="sm"
                icon
                :loading="loadingPasswordShareId === share.id"
                aria-label="查看密码"
                @click="loadSharePassword(share)"
              >
                <Eye :size="14" />
              </OButton>
              <OButton v-if="canManageShare(share)" variant="ghost" size="sm" @click="openEdit(share)">编辑</OButton>
              <OButton variant="ghost" size="sm" @click="copyLink(share)">复制</OButton>
              <OButton v-if="canManageShare(share)" variant="danger" size="sm" @click="deleteShare(share)">取消</OButton>
            </div>
          </div>
        </div>
      </section>

      <section v-if="canManage" class="team-section">
        <div class="team-section__title">
          <h3>审计日志</h3>
          <p>记录团队成员、文件、分享、版本、标签和配额操作 · 共 {{ auditTotal }} 条</p>
        </div>
        <div class="team-audit__filters">
          <OInput v-model="auditAction" placeholder="动作，如 FILE_DELETE" @keyup.enter="filterAuditLogs" />
          <OInput v-model="auditTargetType" placeholder="目标，如 FILE" @keyup.enter="filterAuditLogs" />
          <select v-model="auditResult" @change="filterAuditLogs">
            <option value="">全部结果</option>
            <option value="1">成功</option>
            <option value="0">失败</option>
          </select>
          <OButton variant="ghost" size="sm" @click="filterAuditLogs">筛选</OButton>
          <OButton variant="ghost" size="sm" @click="exportAuditLogs">导出 CSV</OButton>
        </div>
        <div v-if="auditLoading" class="team-settings__inline-loading"><OSpinner /></div>
        <OEmptyState v-else-if="auditLogs.length === 0" title="暂无审计日志" />
        <div v-else class="team-audit">
          <div v-for="log in auditLogs" :key="log.id" class="team-audit__row">
            <div class="team-audit__time font-mono">{{ formatDateFull(log.createdAt) }}</div>
            <div class="team-audit__action font-mono">{{ log.action }}</div>
            <div class="team-audit__target">{{ log.targetType || '-' }} {{ log.targetId || '' }}</div>
            <div class="team-audit__details">{{ log.details || '-' }}</div>
          </div>
        </div>
        <div v-if="auditTotal > TEAM_AUDIT_PAGE_SIZE" class="team-collection__pagination">
          <OButton variant="ghost" size="sm" :disabled="auditPage === 1" @click="goAuditPage(auditPage - 1)">上一页</OButton>
          <span class="font-mono">第 {{ auditPage }} / {{ auditTotalPages }} 页 · 共 {{ auditTotal }} 条日志</span>
          <OButton variant="ghost" size="sm" :disabled="auditPage >= auditTotalPages" @click="goAuditPage(auditPage + 1)">下一页</OButton>
        </div>
      </section>

      <section class="team-section">
        <div class="team-section__title">
          <h3>危险操作</h3>
          <p>退出或删除团队前请确认团队文件归属。</p>
        </div>
        <div class="team-settings__danger">
          <OButton v-if="!isOwner" variant="danger" size="sm" @click="leaveTeam">退出团队</OButton>
          <OButton v-if="isOwner" variant="danger" size="sm" @click="deleteTeam">删除团队</OButton>
        </div>
      </section>
    </template>

    <OModal title="分享详情" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailShare" class="share-info">
        <div><span>链接</span><strong class="font-mono">{{ buildShareUrl(detailShare.shareCode) }}</strong></div>
        <div><span>文件 ID</span><strong class="font-mono">{{ detailShare.fileId }}</strong></div>
        <div><span>访问</span><strong class="font-mono">{{ detailShare.visitCount }}{{ detailShare.maxVisits ? `/${detailShare.maxVisits}` : '' }}</strong></div>
        <div><span>下载</span><strong class="font-mono">{{ detailShare.downloadCount }}</strong></div>
        <div><span>密码</span><strong>{{ detailShare.hasPassword ? (detailPassword || '有密码') : '无密码' }}</strong></div>
        <div><span>过期</span><strong>{{ detailShare.expireAt ? formatDateFull(detailShare.expireAt) : '永不过期' }}</strong></div>
        <div><span>状态</span><strong>{{ detailShare.isCanceled ? '已取消' : isExpired(detailShare) ? '已过期' : '有效' }}</strong></div>
      </div>
    </OModal>

    <OModal title="编辑团队分享" :open="editOpen" @close="editOpen = false">
      <div class="share-edit">
        <div class="share-edit__field">
          <OInput v-model="editMaxVisits" label="访问次数限制" type="number" placeholder="留空表示不限" />
          <OButton variant="ghost" size="sm" @click="editMaxVisits = ''">清空访问次数</OButton>
        </div>
        <div class="share-edit__field">
          <OInput v-model="editExpireAt" label="过期时间" type="datetime-local" />
          <OButton variant="ghost" size="sm" @click="editExpireAt = ''">清空过期时间</OButton>
        </div>
        <OInput v-model="editPassword" label="新访问密码" type="text" placeholder="留空表示不修改" />
        <label class="share-edit__check">
          <input v-model="editClearPassword" type="checkbox" />
          <span>移除访问密码</span>
        </label>
        <p>清除访问次数和过期时间会设为无限制；密码留空表示不修改。</p>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="editOpen = false">取消</OButton>
        <OButton variant="primary" @click="submitEdit">保存</OButton>
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
