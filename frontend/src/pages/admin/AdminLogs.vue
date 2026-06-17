<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import type { OperationLog, SystemLogEntry } from '@/api/admin'

type TabKey = 'audit' | 'system'

const { formatDateFull } = useFormatters()
const activeTab = ref<TabKey>('audit')

const auditLogs = ref<OperationLog[]>([])
const auditLoading = ref(false)
const auditExpanded = ref<Set<number>>(new Set())
const auditFilters = ref({
  userId: '',
  actionType: '',
  requestId: '',
  traceId: '',
  statusCode: '',
  result: '',
  startDate: '',
  endDate: '',
})
const auditPage = ref(1)
const auditTotal = ref(0)
const AUDIT_SIZE = 50

const systemLogs = ref<SystemLogEntry[]>([])
const systemLoading = ref(false)
const systemFilters = ref({
  traceId: '',
  requestId: '',
  level: '',
  keyword: '',
  startTime: '',
  endTime: '',
})
const SYSTEM_LIMIT = 100

const auditPages = computed(() => Math.max(1, Math.ceil(auditTotal.value / AUDIT_SIZE)))

function parseNumber(value: string) {
  if (!value.trim()) return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function toIsoString(value: string) {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

async function loadAuditLogs() {
  auditLoading.value = true
  try {
    const { data } = await adminApi.listLogs({
      userId: parseNumber(auditFilters.value.userId),
      actionType: auditFilters.value.actionType || undefined,
      requestId: auditFilters.value.requestId || undefined,
      traceId: auditFilters.value.traceId || undefined,
      statusCode: parseNumber(auditFilters.value.statusCode),
      result: parseNumber(auditFilters.value.result),
      startDate: toIsoString(auditFilters.value.startDate),
      endDate: toIsoString(auditFilters.value.endDate),
      page: auditPage.value,
      size: AUDIT_SIZE,
    })
    auditLogs.value = data.data.records
    auditTotal.value = data.data.total
  } finally {
    auditLoading.value = false
  }
}

async function loadSystemLogs() {
  systemLoading.value = true
  try {
    const { data } = await adminApi.listSystemLogs({
      traceId: systemFilters.value.traceId || undefined,
      requestId: systemFilters.value.requestId || undefined,
      level: systemFilters.value.level || undefined,
      keyword: systemFilters.value.keyword || undefined,
      startTime: toIsoString(systemFilters.value.startTime),
      endTime: toIsoString(systemFilters.value.endTime),
      limit: SYSTEM_LIMIT,
    })
    systemLogs.value = data.data
  } finally {
    systemLoading.value = false
  }
}

function loadCurrentTab() {
  if (activeTab.value === 'audit') return loadAuditLogs()
  return loadSystemLogs()
}

function selectTab(tab: TabKey) {
  activeTab.value = tab
  loadCurrentTab()
}

function resetAuditFilters() {
  auditFilters.value = {
    userId: '',
    actionType: '',
    requestId: '',
    traceId: '',
    statusCode: '',
    result: '',
    startDate: '',
    endDate: '',
  }
  auditPage.value = 1
  loadAuditLogs()
}

function resetSystemFilters() {
  systemFilters.value = {
    traceId: '',
    requestId: '',
    level: '',
    keyword: '',
    startTime: '',
    endTime: '',
  }
  loadSystemLogs()
}

function actionColor(action: string | null | undefined) {
  const value = action || ''
  if (value.includes('DELETE') || value.includes('PURGE')) return 'var(--danger)'
  if (value.includes('UPLOAD') || value.includes('CREATE')) return 'var(--success)'
  if (value.includes('SHARE')) return 'var(--accent)'
  return 'var(--text-secondary)'
}

function levelColor(level: string | null | undefined) {
  const value = (level || '').toUpperCase()
  if (value === 'ERROR') return 'var(--danger)'
  if (value === 'WARN') return 'var(--warning)'
  if (value === 'INFO') return 'var(--accent)'
  return 'var(--text-secondary)'
}

function resultText(result: number | null | undefined) {
  if (result === 1) return '成功'
  if (result === 0) return '失败'
  return '-'
}

function resultClass(result: number | null | undefined) {
  if (result === 1) return 'badge badge--success'
  if (result === 0) return 'badge badge--danger'
  return 'badge'
}

function formatDetail(detail: string | null | undefined) {
  if (!detail) return ''
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}

function toggleAuditDetail(id: number) {
  const next = new Set(auditExpanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  auditExpanded.value = next
}

function copyText(text: string | null | undefined) {
  if (!text) return
  navigator.clipboard?.writeText(text)
}

function shorten(value: string | null | undefined, head = 10, tail = 6) {
  if (!value) return '-'
  if (value.length <= head + tail + 3) return value
  return `${value.slice(0, head)}...${value.slice(-tail)}`
}

function grafanaHint(log: SystemLogEntry) {
  if (log.idType === 'traceId') return '默认按 traceId'
  if (log.idType === 'requestId') return '默认按 requestId'
  return '按时间范围'
}

async function openGrafana(log: SystemLogEntry) {
  await adminApi.createGrafanaSession()
  window.open(log.grafanaUrl, '_blank', 'noopener,noreferrer')
}

onMounted(loadAuditLogs)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>日志中心</h2>
        <p class="page-header__meta">审计日志来自数据库，系统日志来自 Loki。</p>
      </div>
      <div class="tabs" role="tablist" aria-label="日志类型">
        <button type="button" :class="['tab', { 'tab--active': activeTab === 'audit' }]" @click="selectTab('audit')">审计日志</button>
        <button type="button" :class="['tab', { 'tab--active': activeTab === 'system' }]" @click="selectTab('system')">系统日志</button>
      </div>
    </div>

    <section v-if="activeTab === 'audit'" class="log-panel">
      <div class="filter-grid">
        <OInput v-model="auditFilters.userId" placeholder="用户ID" type="number" />
        <OInput v-model="auditFilters.actionType" placeholder="动作类型" />
        <OInput v-model="auditFilters.requestId" placeholder="Request ID" />
        <OInput v-model="auditFilters.traceId" placeholder="Trace ID" />
        <OInput v-model="auditFilters.statusCode" placeholder="状态码" type="number" />
        <select v-model="auditFilters.result" class="filter-select">
          <option value="">全部结果</option>
          <option value="1">成功</option>
          <option value="0">失败</option>
        </select>
        <OInput v-model="auditFilters.startDate" type="datetime-local" />
        <OInput v-model="auditFilters.endDate" type="datetime-local" />
        <div class="filter-actions">
          <OButton variant="primary" size="sm" @click="() => { auditPage = 1; loadAuditLogs() }">筛选</OButton>
          <OButton variant="subtle" size="sm" @click="resetAuditFilters">重置</OButton>
        </div>
      </div>

      <div v-if="auditLoading" class="loading"><OSpinner /></div>
      <OEmptyState v-else-if="auditLogs.length === 0" title="暂无审计日志" />
      <div v-else class="log-table">
        <div class="log-table__head audit-grid">
          <span>时间</span>
          <span>结果</span>
          <span>动作</span>
          <span>用户</span>
          <span>目标</span>
          <span>状态</span>
          <span>耗时</span>
          <span>Trace ID</span>
          <span>详情</span>
        </div>
        <div v-for="log in auditLogs" :key="log.id" class="log-row-wrap">
          <div class="log-row audit-grid">
            <span class="font-mono">{{ formatDateFull(log.createdAt) }}</span>
            <span :class="resultClass(log.result)">{{ resultText(log.result) }}</span>
            <span class="font-mono" :style="{ color: actionColor(log.actionType) }">{{ log.actionType }}</span>
            <span class="font-mono">{{ log.userId ?? '-' }}</span>
            <span>{{ log.targetType || '-' }}<span v-if="log.targetId" class="font-mono"> #{{ log.targetId }}</span></span>
            <span class="font-mono">{{ log.statusCode ?? '-' }}</span>
            <span class="font-mono">{{ log.durationMs ?? '-' }} ms</span>
            <button class="id-button font-mono" :title="log.traceId || ''" @click="copyText(log.traceId)">
              {{ shorten(log.traceId) }}
            </button>
            <OButton variant="ghost" size="sm" @click="toggleAuditDetail(log.id)">
              {{ auditExpanded.has(log.id) ? '收起' : '展开' }}
            </OButton>
          </div>
          <div v-if="auditExpanded.has(log.id)" class="log-detail">
            <div class="detail-meta">
              <button class="id-button font-mono" @click="copyText(log.requestId)">requestId: {{ log.requestId || '-' }}</button>
              <span>IP: {{ log.ipAddress || '-' }}</span>
              <span>UA: {{ log.userAgent || '-' }}</span>
            </div>
            <pre>{{ formatDetail(log.detail) || '无详情' }}</pre>
          </div>
        </div>
      </div>

      <div v-if="auditTotal > AUDIT_SIZE" class="pagination">
        <OButton variant="ghost" size="sm" :disabled="auditPage === 1" @click="() => { auditPage--; loadAuditLogs() }">上一页</OButton>
        <span class="pagination__info font-mono">{{ auditPage }} / {{ auditPages }}</span>
        <OButton variant="ghost" size="sm" :disabled="auditPage * AUDIT_SIZE >= auditTotal" @click="() => { auditPage++; loadAuditLogs() }">下一页</OButton>
      </div>
    </section>

    <section v-else class="log-panel">
      <div class="filter-grid system-filter-grid">
        <OInput v-model="systemFilters.traceId" placeholder="Trace ID" />
        <OInput v-model="systemFilters.requestId" placeholder="Request ID" />
        <OInput v-model="systemFilters.level" placeholder="级别 ERROR/WARN/INFO" />
        <OInput v-model="systemFilters.keyword" placeholder="关键词" />
        <OInput v-model="systemFilters.startTime" type="datetime-local" />
        <OInput v-model="systemFilters.endTime" type="datetime-local" />
        <div class="filter-actions">
          <OButton variant="primary" size="sm" @click="loadSystemLogs">筛选</OButton>
          <OButton variant="subtle" size="sm" @click="resetSystemFilters">重置</OButton>
        </div>
      </div>

      <div v-if="systemLoading" class="loading"><OSpinner /></div>
      <OEmptyState v-else-if="systemLogs.length === 0" title="暂无系统日志" />
      <div v-else class="log-table">
        <div class="log-table__head system-grid">
          <span>时间</span>
          <span>级别</span>
          <span>ID</span>
          <span>类型</span>
          <span>路径</span>
          <span>Logger</span>
          <span>概要</span>
          <span>Grafana</span>
        </div>
        <div v-for="log in systemLogs" :key="`${log.idType}:${log.id}:${log.timestamp}`" class="log-row system-grid">
          <span class="font-mono">{{ formatDateFull(log.timestamp) }}</span>
          <span class="font-mono" :style="{ color: levelColor(log.level) }">{{ log.level || '-' }}</span>
          <button class="id-button font-mono" :title="log.id" @click="copyText(log.id)">{{ shorten(log.id) }}</button>
          <span>{{ log.idType }}</span>
          <span class="truncate">{{ log.method ? `${log.method} ` : '' }}{{ log.path || '-' }}</span>
          <span class="truncate" :title="log.logger || ''">{{ log.logger || '-' }}</span>
          <span class="truncate" :title="log.message || ''">{{ log.message || '-' }}</span>
          <div class="grafana-cell">
            <span class="grafana-hint">{{ grafanaHint(log) }}</span>
            <button type="button" class="grafana-link" @click="openGrafana(log)">Grafana</button>
          </div>
        </div>
      </div>
    </section>
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

.page-header__meta {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
}

.tabs {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}

.tab {
  min-width: 86px;
  height: 30px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font: inherit;
}

.tab--active {
  background: var(--bg-overlay);
  color: var(--text-primary);
}

.log-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(130px, 1fr));
  gap: 8px;
  align-items: center;
}

.system-filter-grid {
  grid-template-columns: repeat(3, minmax(160px, 1fr));
}

.filter-select {
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  padding: 0 10px;
  font: inherit;
}

.filter-actions {
  display: flex;
  gap: 8px;
}

.loading {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.log-table {
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
  background: var(--bg-elevated);
}

.log-table__head,
.log-row {
  display: grid;
  gap: 10px;
  align-items: center;
  min-width: 960px;
}

.audit-grid {
  grid-template-columns: 150px 58px 82px 56px 100px 58px 76px 132px 70px;
}

.system-grid {
  grid-template-columns: 150px 58px 132px 76px 150px 170px minmax(220px, 1fr) 64px;
}

.log-table__head {
  padding: 10px 12px;
  color: var(--text-muted);
  font-size: 11px;
  text-transform: uppercase;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
}

.log-row {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.log-row-wrap:last-child .log-row,
.log-row:last-child {
  border-bottom: 0;
}

.log-detail {
  padding: 0 12px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.log-detail pre {
  max-height: 320px;
  overflow: auto;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-base);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.badge {
  display: inline-flex;
  justify-content: center;
  width: fit-content;
  min-width: 40px;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  font-size: 11px;
}

.badge--success {
  color: var(--success);
  border-color: rgba(46, 213, 115, 0.25);
}

.badge--danger {
  color: var(--danger);
  border-color: rgba(255, 71, 87, 0.25);
}

.id-button {
  display: inline;
  width: fit-content;
  max-width: 100%;
  border: 0;
  background: transparent;
  color: var(--accent);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grafana-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
}

.grafana-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.grafana-hint {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  background: var(--bg-surface);
  white-space: nowrap;
}

.pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
  margin-top: 6px;
}

.pagination__info {
  font-size: 12px;
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .tabs {
    width: fit-content;
  }

  .filter-grid,
  .system-filter-grid {
    grid-template-columns: 1fr;
  }

  .filter-actions {
    width: 100%;
  }

  .log-table {
    overflow-x: auto;
  }
}
</style>
