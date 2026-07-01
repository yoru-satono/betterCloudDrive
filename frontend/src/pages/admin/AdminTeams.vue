<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import { toast } from 'vue-sonner'
import type { AdminTeam } from '@/api/admin'
import type { FileEntity } from '@/types/file'
import type { TeamMember, TeamOperationLog } from '@/types/team'

const { formatSize, formatDate } = useFormatters()
const teams = ref<AdminTeam[]>([])
const loading = ref(false)
const search = ref('')
const status = ref<'active' | 'archived' | 'deleted' | 'all'>('active')
const page = ref(1)
const total = ref(0)
const editingQuota = ref<{ id: number; val: string } | null>(null)
const PAGE_SIZE = 20
const DETAIL_PAGE_SIZE = 10
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const detailOpen = ref(false)
const detailTeam = ref<AdminTeam | null>(null)
const detailTab = ref<'members' | 'files' | 'audit'>('members')
const detailLoading = ref(false)
const teamMembers = ref<TeamMember[]>([])
const teamFiles = ref<FileEntity[]>([])
const teamFilePage = ref(1)
const teamFileTotal = ref(0)
const teamFileKeyword = ref('')
const teamFileDeleted = ref<'active' | 'deleted' | 'all'>('active')
const teamAuditLogs = ref<TeamOperationLog[]>([])
const teamAuditPage = ref(1)
const teamAuditTotal = ref(0)
const teamAuditAction = ref('')
const teamAuditTargetType = ref('')
const teamAuditResult = ref<'' | '1' | '0'>('')
const teamFileTotalPages = computed(() => Math.max(1, Math.ceil(teamFileTotal.value / DETAIL_PAGE_SIZE)))
const teamAuditTotalPages = computed(() => Math.max(1, Math.ceil(teamAuditTotal.value / DETAIL_PAGE_SIZE)))

async function load() {
  loading.value = true
  try {
    const { data } = await adminApi.listTeams(page.value, PAGE_SIZE, search.value || undefined, status.value)
    teams.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

function searchTeams() {
  page.value = 1
  load()
}

function goPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), totalPages.value)
  if (normalizedPage === page.value) return
  page.value = normalizedPage
  load()
}

async function saveQuota(team: AdminTeam) {
  if (!editingQuota.value) return
  const quotaGb = Number(editingQuota.value.val)
  if (!Number.isFinite(quotaGb) || quotaGb <= 0) {
    toast.error('请输入有效的团队配额')
    return
  }
  await adminApi.updateTeamQuota(team.id, Math.round(quotaGb * 1024 * 1024 * 1024))
  toast.success('团队配额已更新')
  editingQuota.value = null
  await load()
}

async function openDetail(team: AdminTeam) {
  detailTeam.value = team
  detailOpen.value = true
  detailTab.value = 'members'
  teamFilePage.value = 1
  teamAuditPage.value = 1
  await loadDetail()
}

async function refreshDetail() {
  if (!detailTeam.value) return
  const { data } = await adminApi.getTeam(detailTeam.value.id)
  detailTeam.value = data.data
  await loadDetail()
}

async function loadDetail() {
  if (!detailTeam.value) return
  detailLoading.value = true
  try {
    await Promise.all([loadMembers(), loadTeamFiles(), loadTeamAuditLogs()])
  } finally {
    detailLoading.value = false
  }
}

async function loadMembers() {
  if (!detailTeam.value) return
  const { data } = await adminApi.listTeamMembers(detailTeam.value.id)
  teamMembers.value = data.data
}

async function loadTeamFiles() {
  if (!detailTeam.value) return
  const deleted = teamFileDeleted.value === 'all' ? undefined : teamFileDeleted.value === 'deleted'
  const { data } = await adminApi.listTeamFiles(detailTeam.value.id, {
    page: teamFilePage.value,
    size: DETAIL_PAGE_SIZE,
    deleted,
    keyword: teamFileKeyword.value.trim() || undefined,
  })
  teamFiles.value = data.data.records
  teamFileTotal.value = data.data.total
}

async function loadTeamAuditLogs() {
  if (!detailTeam.value) return
  const { data } = await adminApi.listTeamAuditLogs(detailTeam.value.id, {
    page: teamAuditPage.value,
    size: DETAIL_PAGE_SIZE,
    action: teamAuditAction.value.trim() || undefined,
    targetType: teamAuditTargetType.value.trim() || undefined,
    result: teamAuditResult.value === '' ? undefined : Number(teamAuditResult.value),
  })
  teamAuditLogs.value = data.data.records
  teamAuditTotal.value = data.data.total
}

async function exportAuditLogs() {
  if (!detailTeam.value) return
  const response = await adminApi.exportTeamAuditLogs(detailTeam.value.id, {
    action: teamAuditAction.value.trim() || undefined,
    targetType: teamAuditTargetType.value.trim() || undefined,
    result: teamAuditResult.value === '' ? undefined : Number(teamAuditResult.value),
  })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `team-${detailTeam.value.id}-audit.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
  toast.success('审计日志已导出')
}

async function archiveTeam(team: AdminTeam) {
  await adminApi.archiveTeam(team.id)
  toast.success('团队已归档')
  await load()
  if (detailOpen.value && detailTeam.value?.id === team.id) await refreshDetail()
}

async function unarchiveTeam(team: AdminTeam) {
  await adminApi.unarchiveTeam(team.id)
  toast.success('团队已取消归档')
  await load()
  if (detailOpen.value && detailTeam.value?.id === team.id) await refreshDetail()
}

async function restoreTeam(team: AdminTeam) {
  await adminApi.restoreTeam(team.id)
  toast.success('团队已恢复')
  await load()
  if (detailOpen.value && detailTeam.value?.id === team.id) await refreshDetail()
}

async function purgeTeam(team: AdminTeam) {
  const purgeText = team.purgeAfter ? `\n计划清理时间：${formatDate(team.purgeAfter)}` : ''
  if (!confirm(`确认永久清理「${team.name}」？该操作会删除团队文件和数据。${purgeText}`)) return
  await adminApi.purgeTeam(team.id)
  toast.success('团队已清理')
  if (detailTeam.value?.id === team.id) detailOpen.value = false
  await load()
}

function teamStatus(team: AdminTeam) {
  if (team.deletedAt) return '已删除'
  if (team.archivedAt) return '已归档'
  return '活跃'
}

function teamStatusDetail(team: AdminTeam) {
  if (team.deletedAt) return `删除于 ${formatDate(team.deletedAt)}${team.purgeAfter ? ` · 清理 ${formatDate(team.purgeAfter)}` : ''}`
  if (team.archivedAt) return `归档于 ${formatDate(team.archivedAt)}`
  return '可正常访问'
}

function quotaPercent(team: AdminTeam) {
  if (!team.storageQuota || team.storageQuota <= 0) return 0
  return Math.min(100, Math.round((team.storageUsed / team.storageQuota) * 100))
}

function quotaState(team: AdminTeam) {
  const percent = quotaPercent(team)
  return percent >= 100 ? 'danger' : percent >= 90 ? 'warning' : percent >= 80 ? 'notice' : 'normal'
}

function searchTeamFiles() {
  teamFilePage.value = 1
  loadTeamFiles()
}

function filterTeamAuditLogs() {
  teamAuditPage.value = 1
  loadTeamAuditLogs()
}

function goTeamFilePage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), teamFileTotalPages.value)
  if (normalizedPage === teamFilePage.value) return
  teamFilePage.value = normalizedPage
  loadTeamFiles()
}

function goTeamAuditPage(nextPage: number) {
  const normalizedPage = Math.min(Math.max(nextPage, 1), teamAuditTotalPages.value)
  if (normalizedPage === teamAuditPage.value) return
  teamAuditPage.value = normalizedPage
  loadTeamAuditLogs()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <h2>团队管理</h2>
      <div class="teams-toolbar">
        <OInput v-model="search" placeholder="搜索团队..." class="teams-toolbar__search" @keyup.enter="searchTeams" />
        <select v-model="status" class="teams-toolbar__status" @change="searchTeams">
          <option value="active">活跃</option>
          <option value="archived">已归档</option>
          <option value="deleted">已删除</option>
          <option value="all">全部</option>
        </select>
        <OButton variant="ghost" size="sm" @click="searchTeams">搜索</OButton>
      </div>
    </div>

    <div v-if="total > PAGE_SIZE" class="pagination pagination--top">
      <OButton variant="ghost" size="sm" :disabled="page === 1" @click="goPage(page - 1)">上一页</OButton>
      <span class="pagination__info font-mono">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 个团队</span>
      <OButton variant="ghost" size="sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</OButton>
    </div>

    <div v-if="loading" class="teams-loading"><OSpinner /></div>
    <div v-else class="teams-table">
      <div class="teams-table__header">
        <div>团队</div><div>Owner ID</div><div>存储</div><div>状态</div><div>创建时间</div><div>操作</div>
      </div>
      <div v-for="team in teams" :key="team.id" class="teams-table__row">
        <div class="teams-table__name">{{ team.name }}</div>
        <div class="font-mono">{{ team.ownerId }}</div>
        <div>
          <template v-if="editingQuota?.id === team.id">
            <div class="quota-editor">
              <input v-model="editingQuota.val" type="number" min="0.01" step="0.01" @keyup.enter="saveQuota(team)" @keyup.escape="editingQuota=null" />
              <span>GB</span>
              <OButton variant="primary" size="sm" @click="saveQuota(team)">保存</OButton>
            </div>
          </template>
          <button v-else class="quota-btn" @click="editingQuota = { id: team.id, val: (team.storageQuota / 1073741824).toFixed(0) }">
            <span>{{ formatSize(team.storageUsed) }} / {{ formatSize(team.storageQuota) }}</span>
            <span class="quota-btn__bar" :class="`quota-btn__bar--${quotaState(team)}`">
              <span :style="{ width: `${quotaPercent(team)}%` }" />
            </span>
          </button>
        </div>
        <div class="teams-table__status">
          <strong>{{ teamStatus(team) }}</strong>
          <span>{{ teamStatusDetail(team) }}</span>
        </div>
        <div class="teams-table__date">{{ formatDate(team.createdAt) }}</div>
        <div class="teams-table__actions">
          <OButton variant="ghost" size="sm" @click="openDetail(team)">详情</OButton>
          <OButton variant="subtle" size="sm" @click="editingQuota = { id: team.id, val: (team.storageQuota / 1073741824).toFixed(0) }">配额</OButton>
          <OButton v-if="!team.deletedAt && !team.archivedAt" variant="ghost" size="sm" @click="archiveTeam(team)">归档</OButton>
          <OButton v-else-if="team.archivedAt" variant="ghost" size="sm" @click="unarchiveTeam(team)">取消归档</OButton>
          <OButton v-if="team.deletedAt" variant="ghost" size="sm" @click="restoreTeam(team)">恢复</OButton>
          <OButton v-if="team.deletedAt" variant="danger" size="sm" @click="purgeTeam(team)">清理</OButton>
        </div>
      </div>
    </div>

    <div v-if="!loading && total > PAGE_SIZE" class="pagination pagination--bottom">
      <OButton variant="ghost" size="sm" :disabled="page === 1" @click="goPage(page - 1)">上一页</OButton>
      <span class="pagination__info font-mono">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 个团队</span>
      <OButton variant="ghost" size="sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</OButton>
    </div>

    <OModal
      :title="detailTeam ? `团队详情：${detailTeam.name}` : '团队详情'"
      :open="detailOpen"
      width="920px"
      body-class="o-modal__body--fill"
      @close="detailOpen = false"
    >
      <div v-if="detailTeam" class="team-detail">
        <div class="team-detail__summary">
          <span class="font-mono">ID {{ detailTeam.id }}</span>
          <span>Owner {{ detailTeam.ownerId }}</span>
          <span>{{ teamStatus(detailTeam) }}</span>
          <span>{{ teamStatusDetail(detailTeam) }}</span>
          <span>{{ formatSize(detailTeam.storageUsed) }} / {{ formatSize(detailTeam.storageQuota) }}</span>
          <span>{{ formatDate(detailTeam.createdAt) }}</span>
        </div>

        <div class="team-detail__tabs">
          <button :class="{ active: detailTab === 'members' }" @click="detailTab = 'members'">成员</button>
          <button :class="{ active: detailTab === 'files' }" @click="detailTab = 'files'">文件</button>
          <button :class="{ active: detailTab === 'audit' }" @click="detailTab = 'audit'">审计</button>
        </div>

        <div v-if="detailLoading" class="teams-loading"><OSpinner /></div>

        <div v-else-if="detailTab === 'members'" class="detail-table detail-table--members">
          <div class="detail-table__header"><div>用户</div><div>邮箱</div><div>角色</div><div>加入时间</div></div>
          <div v-for="member in teamMembers" :key="member.userId" class="detail-table__row">
            <div>{{ member.username || '-' }}</div>
            <div class="truncate">{{ member.email || '-' }}</div>
            <div>{{ member.role }}</div>
            <div>{{ formatDate(member.createdAt) }}</div>
          </div>
          <div v-if="teamMembers.length === 0" class="detail-empty">暂无成员</div>
        </div>

        <div v-else-if="detailTab === 'files'" class="team-detail__panel">
          <div class="team-detail__toolbar">
            <OInput v-model="teamFileKeyword" placeholder="搜索团队文件..." @keyup.enter="searchTeamFiles" />
            <select v-model="teamFileDeleted" @change="searchTeamFiles">
              <option value="active">正常文件</option>
              <option value="deleted">回收站文件</option>
              <option value="all">全部</option>
            </select>
            <OButton variant="ghost" size="sm" @click="searchTeamFiles">搜索</OButton>
          </div>
          <div class="detail-table detail-table--files">
            <div class="detail-table__header"><div>名称</div><div>类型</div><div>大小</div><div>状态</div><div>更新时间</div></div>
            <div v-for="file in teamFiles" :key="file.id" class="detail-table__row">
              <div class="truncate">{{ file.fileName }}</div>
              <div>{{ file.fileType === 'folder' ? '文件夹' : '文件' }}</div>
              <div class="font-mono">{{ formatSize(file.fileSize) }}</div>
              <div>{{ file.isDeleted ? '已删除' : '正常' }}</div>
              <div>{{ formatDate(file.updatedAt) }}</div>
            </div>
            <div v-if="teamFiles.length === 0" class="detail-empty">暂无文件</div>
          </div>
          <div class="pagination pagination--compact">
            <OButton variant="ghost" size="sm" :disabled="teamFilePage === 1" @click="goTeamFilePage(teamFilePage - 1)">上一页</OButton>
            <span class="pagination__info font-mono">第 {{ teamFilePage }} / {{ teamFileTotalPages }} 页 · 共 {{ teamFileTotal }} 个文件</span>
            <OButton variant="ghost" size="sm" :disabled="teamFilePage >= teamFileTotalPages" @click="goTeamFilePage(teamFilePage + 1)">下一页</OButton>
          </div>
        </div>

        <div v-else class="team-detail__panel">
          <div class="team-detail__toolbar">
            <OInput v-model="teamAuditAction" placeholder="按动作过滤，如 FILE_DELETE" @keyup.enter="filterTeamAuditLogs" />
            <OInput v-model="teamAuditTargetType" placeholder="目标，如 FILE" @keyup.enter="filterTeamAuditLogs" />
            <select v-model="teamAuditResult" @change="filterTeamAuditLogs">
              <option value="">全部结果</option>
              <option value="1">成功</option>
              <option value="0">失败</option>
            </select>
            <OButton variant="ghost" size="sm" @click="filterTeamAuditLogs">筛选</OButton>
            <OButton variant="subtle" size="sm" @click="exportAuditLogs">导出 CSV</OButton>
          </div>
          <div class="detail-table detail-table--audit">
            <div class="detail-table__header"><div>时间</div><div>动作</div><div>用户</div><div>目标</div><div>结果</div><div>详情</div></div>
            <div v-for="log in teamAuditLogs" :key="log.id" class="detail-table__row">
              <div class="font-mono">{{ formatDate(log.createdAt) }}</div>
              <div>{{ log.action }}</div>
              <div class="font-mono">{{ log.actorUserId || '-' }}</div>
              <div>{{ log.targetType || '-' }} {{ log.targetId || '' }}</div>
              <div>{{ log.result === 1 ? '成功' : '失败' }}</div>
              <div class="truncate">{{ log.details || '-' }}</div>
            </div>
            <div v-if="teamAuditLogs.length === 0" class="detail-empty">暂无审计日志</div>
          </div>
          <div class="pagination pagination--compact">
            <OButton variant="ghost" size="sm" :disabled="teamAuditPage === 1" @click="goTeamAuditPage(teamAuditPage - 1)">上一页</OButton>
            <span class="pagination__info font-mono">第 {{ teamAuditPage }} / {{ teamAuditTotalPages }} 页 · 共 {{ teamAuditTotal }} 条日志</span>
            <OButton variant="ghost" size="sm" :disabled="teamAuditPage >= teamAuditTotalPages" @click="goTeamAuditPage(teamAuditPage + 1)">下一页</OButton>
          </div>
        </div>
      </div>
    </OModal>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.teams-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.teams-toolbar__search { width: 220px; }
.teams-toolbar__status {
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
}
.teams-loading {
  display: flex;
  justify-content: center;
  padding: 60px;
}
.teams-table {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow-x: auto;
}
.teams-table__header,
.teams-table__row {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) 100px minmax(220px, 1fr) 90px 140px 220px;
  gap: 10px;
  min-width: 980px;
  align-items: center;
  padding: 10px 12px;
}
.teams-table__header {
  color: var(--text-muted);
  font-size: 11px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
.teams-table__row {
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.teams-table__row:last-child { border-bottom: 0; }
.teams-table__name { font-weight: 500; }
.teams-table__date { color: var(--text-secondary); font-size: 12px; }
.teams-table__status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--text-secondary);
  font-size: 12px;
}
.teams-table__status strong {
  color: var(--text-primary);
  font-weight: 600;
}
.teams-table__status span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.teams-table__actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.quota-editor {
  display: flex;
  align-items: center;
  gap: 6px;
}
.quota-editor input {
  width: 76px;
  border: 1px solid var(--border-focus);
  border-radius: 6px;
  background: var(--bg-overlay);
  color: var(--text-primary);
  padding: 4px 8px;
}
.quota-btn {
  border: 1px dashed var(--border);
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  padding: 3px 8px;
  cursor: pointer;
  font-family: var(--font-mono);
  font-size: 12px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  text-align: left;
}
.quota-btn__bar {
  height: 4px;
  border-radius: 999px;
  background: var(--bg-elevated);
  overflow: hidden;
}
.quota-btn__bar span {
  display: block;
  height: 100%;
  background: var(--accent);
}
.quota-btn__bar--notice span { background: #f59e0b; }
.quota-btn__bar--warning span,
.quota-btn__bar--danger span { background: var(--danger); }
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.pagination--top { margin-bottom: 12px; }
.pagination--bottom { margin-top: 12px; }
.pagination--compact {
  margin-top: 10px;
  padding: 8px;
}
.pagination__info { color: var(--text-secondary); font-size: 12px; }
.team-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
}
.team-detail__summary {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: var(--text-secondary);
  font-size: 12px;
}
.team-detail__tabs {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--border);
}
.team-detail__tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-secondary);
  padding: 8px 12px;
  cursor: pointer;
}
.team-detail__tabs button.active {
  border-bottom-color: var(--accent);
  color: var(--text-primary);
}
.team-detail__panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.team-detail__toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.team-detail__toolbar :deep(.o-input) {
  width: 240px;
}
.team-detail__toolbar select {
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-overlay);
  color: var(--text-primary);
  padding: 7px 8px;
}
.detail-table {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow-x: auto;
}
.detail-table__header,
.detail-table__row {
  display: grid;
  gap: 10px;
  align-items: center;
  min-width: 760px;
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}
.detail-table__header {
  color: var(--text-muted);
  background: var(--bg-overlay);
}
.detail-table__row:last-child { border-bottom: 0; }
.detail-table--members .detail-table__header,
.detail-table--members .detail-table__row {
  grid-template-columns: minmax(130px, 1fr) minmax(180px, 1.2fr) 90px 140px;
}
.detail-table--files .detail-table__header,
.detail-table--files .detail-table__row {
  grid-template-columns: minmax(220px, 1.4fr) 80px 110px 80px 140px;
}
.detail-table--audit .detail-table__header,
.detail-table--audit .detail-table__row {
  grid-template-columns: 150px minmax(130px, 1fr) 80px 130px 70px minmax(180px, 1.2fr);
}
.detail-empty {
  padding: 18px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
