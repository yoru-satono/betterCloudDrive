<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import OButton from '@/components/base/OButton.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import WarehouseTrend from '@/components/admin/WarehouseTrend.vue'
import * as warehouse from '@/api/warehouse'
import * as admin from '@/api/admin'
import { useFormatters } from '@/composables/useFormatters'

const { t, locale } = useI18n()
const { formatSize } = useFormatters()
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
function subtractDays(day: string, days: number) {
  const date = new Date(`${day}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().slice(0, 10)
}
const start = ref(subtractDays(today, 29))
const end = ref(today)
const scope = ref<warehouse.WarehouseFilter['scope']>('ALL')
const selectedTeam = ref('')
const teamSearch = ref('')
const teamOptions = ref<admin.AdminTeam[]>([])
const teamSearchFailed = ref(false)
const page = ref(1)
const report = ref<warehouse.WarehouseOverview | null>(null)
const series = ref<warehouse.WarehouseDay[]>([])
const teamRows = ref<warehouse.WarehouseTeams | null>(null)
const runs = ref<warehouse.WarehouseRun[]>([])
const loading = ref(false)
const failure = ref(false)
const validation = ref(false)
let controller: AbortController | undefined
let revision = 0
let teamSearchRevision = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
const teamNames = computed(() => new Map(teamOptions.value.map((team) => [team.id, team.name])))

function number(metrics: warehouse.WarehouseMetrics | undefined, key: string) {
  return metrics?.[key] == null ? null : Number(metrics[key])
}
function display(value: number | null) { return value == null ? t('warehouse.noData') : value.toLocaleString(locale.value) }
function metricSize(metrics: warehouse.WarehouseMetrics, key: string) {
  const value = number(metrics, key)
  return value == null ? t('warehouse.noData') : formatSize(value)
}
function dateTime(value: string | number | null | undefined) {
  return value == null ? t('warehouse.noData') : new Date(value).toLocaleString(locale.value, { timeZone: 'Asia/Shanghai' })
}
const logicalBytes = computed(() => report.value?.capacity.current_bytes == null ? null :
  Number(report.value.capacity.current_bytes) + Number(report.value.capacity.trash_bytes) + Number(report.value.capacity.version_bytes))
const accountScope = computed(() => report.value?.meta.scope !== 'TEAM')
const capacityParts = computed(() => [
  { key: 'current_bytes', label: t('warehouse.currentBytes') },
  { key: 'trash_bytes', label: t('warehouse.trashBytes') },
  { key: 'version_bytes', label: t('warehouse.versionBytes') },
].map((part) => ({ ...part, value: number(report.value?.capacity, part.key) })))

async function load() {
  const days = (Date.parse(end.value) - Date.parse(start.value)) / 86400000
  validation.value = !start.value || !end.value || days < 0 || days > 364 || end.value > today || start.value < subtractDays(today, 364)
  if (validation.value) return
  controller?.abort()
  controller = new AbortController()
  const current = ++revision
  loading.value = true
  failure.value = false
  const filter: warehouse.WarehouseFilter = { start: start.value, end: end.value, scope: scope.value }
  if (scope.value === 'TEAM' && selectedTeam.value) filter.teamId = Number(selectedTeam.value)
  try {
    const overview = await warehouse.overview(filter, controller.signal)
    if (current !== revision) return
    const published = { ...filter, generationId: overview.data.data.meta.generationId || undefined }
    const [trends, teams, history] = await Promise.all([
      warehouse.trends(published, controller.signal), warehouse.teams(published, page.value, controller.signal), warehouse.runs(controller.signal),
    ])
    if (current !== revision) return
    report.value = overview.data.data
    series.value = trends.data.data.days
    teamRows.value = teams.data.data
    runs.value = history.data.data
  } catch {
    if (current === revision && !controller.signal.aborted) failure.value = true
  } finally {
    if (current === revision) loading.value = false
  }
}
async function findTeams() {
  const current = ++teamSearchRevision
  try {
    const response = await admin.listTeams(1, 50, teamSearch.value || undefined, 'all')
    if (current !== teamSearchRevision) return
    const selected = teamOptions.value.find((team) => String(team.id) === selectedTeam.value)
    teamOptions.value = response.data.data.records
    if (selected && !teamOptions.value.some((team) => team.id === selected.id)) teamOptions.value.unshift(selected)
    teamSearchFailed.value = false
  } catch { if (current === teamSearchRevision) teamSearchFailed.value = true }
}
function preset(days: number) { end.value = today; start.value = subtractDays(today, days - 1); page.value = 1; void load() }
function paginate(delta: number) { page.value += delta; void load() }
watch(teamSearch, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => void findTeams(), 300) })
watch(scope, () => { selectedTeam.value = ''; page.value = 1 })
onMounted(() => { void load(); void findTeams() })
onBeforeUnmount(() => { revision++; teamSearchRevision++; controller?.abort(); clearTimeout(searchTimer) })
</script>

<template>
  <div class="analytics page-enter" :aria-busy="loading">
    <header class="page-heading">
      <div><h1>{{ t('warehouse.title') }}</h1><p>{{ t('warehouse.cadence') }}</p></div>
      <OButton :loading="loading" @click="load">{{ t('warehouse.refresh') }}</OButton>
    </header>
    <form class="filters" @submit.prevent="page = 1; load()">
      <div class="presets"><OButton v-for="days in [7, 30, 90]" :key="days" size="sm" @click.prevent="preset(days)">{{ t('warehouse.lastDays', { days }) }}</OButton></div>
      <label>{{ t('warehouse.start') }}<input v-model="start" type="date" :min="subtractDays(today, 364)" :max="end" required></label>
      <label>{{ t('warehouse.end') }}<input v-model="end" type="date" :min="start" :max="today" required></label>
      <label>{{ t('warehouse.scope') }}<select v-model="scope"><option value="ALL">{{ t('warehouse.all') }}</option><option value="PERSONAL">{{ t('warehouse.personal') }}</option><option value="TEAM">{{ t('warehouse.teamSpace') }}</option></select></label>
      <template v-if="scope === 'TEAM'">
        <label>{{ t('warehouse.searchTeams') }}<input v-model="teamSearch" type="search"></label>
        <label>{{ t('warehouse.team') }}<select v-model="selectedTeam"><option value="">{{ t('warehouse.allTeams') }}</option><option v-for="team in teamOptions" :key="team.id" :value="String(team.id)">{{ team.name }}</option></select></label>
      </template>
      <OButton type="submit" variant="primary" :loading="loading">{{ t('warehouse.apply') }}</OButton>
    </form>
    <p v-if="validation" role="alert" class="notice danger">{{ t('warehouse.invalidRange') }}</p>
    <p v-if="teamSearchFailed && scope === 'TEAM'" role="alert" class="notice">{{ t('warehouse.teamSearchFailed') }} <button class="text-button" @click="findTeams">{{ t('warehouse.retry') }}</button></p>
    <div v-if="failure" role="alert" class="notice danger">{{ t('warehouse.unavailable') }} <button class="text-button" @click="load">{{ t('warehouse.retry') }}</button></div>
    <div v-if="loading && !report" class="loading"><OSpinner /></div>
    <section v-else-if="report?.meta.status === 'UNINITIALIZED'" class="empty"><h2>{{ t('warehouse.awaitingFirstRun') }}</h2><p>{{ t('warehouse.awaitingFirstRunDetail') }}</p></section>
    <template v-else-if="report">
      <div class="freshness"><span>{{ report.meta.startDate }} — {{ report.meta.endDate }}</span><span>{{ t(report.meta.scope === 'ALL' ? 'warehouse.all' : report.meta.scope === 'PERSONAL' ? 'warehouse.personal' : 'warehouse.teamSpace') }}<template v-if="report.meta.scopeId"> · {{ teamNames.get(report.meta.scopeId) || t('warehouse.teamIdentity', { id: report.meta.scopeId }) }}</template></span><span v-if="report.meta.coverageStart">{{ t('warehouse.coverageStart') }} {{ report.meta.coverageStart }}</span><span>{{ t('warehouse.dataThrough') }} {{ dateTime(report.meta.dataThrough) }}</span><span>{{ t('warehouse.published') }} {{ dateTime(report.meta.publishedAt) }}</span><span v-if="report.meta.stale" class="warning">{{ t('warehouse.stale') }}</span></div>
      <section class="headline-metrics">
        <div><span>{{ t('warehouse.logicalBytes') }}</span><strong>{{ logicalBytes == null ? t('warehouse.noData') : formatSize(logicalBytes) }}</strong><small>{{ t('warehouse.sampled') }} {{ dateTime(Number(report.capacity.sampled_at) || null) }}</small></div>
        <div><span>{{ t('warehouse.activeUsers') }}</span><strong>{{ display(number(report.usage, 'active_users')) }}</strong><small>{{ report.usage.active_day }}</small></div>
        <div><span>{{ t('warehouse.uploads') }}</span><strong>{{ display(number(report.usage, 'uploads')) }}</strong><small>{{ t('warehouse.logicalUploadBytes') }} {{ metricSize(report.usage, 'uploaded_logical_bytes') }}</small></div>
        <div><span>{{ t('warehouse.downloads') }}</span><strong>{{ display(number(report.usage, 'download_attempts')) }}</strong><small>{{ t('warehouse.writtenBytes') }} {{ metricSize(report.usage, 'download_written_bytes') }}</small></div>
      </section>
      <div class="analysis-grid">
        <WarehouseTrend :days="series" />
        <section class="panel capacity-panel"><h2>{{ t('warehouse.storageComposition') }}</h2>
          <div v-for="part in capacityParts" :key="part.key" class="capacity-item"><div><span>{{ part.label }}</span><strong>{{ part.value == null ? t('warehouse.noData') : formatSize(part.value) }}</strong></div><div class="bar-track"><span :class="part.key" :style="{ width: `${logicalBytes ? Number(part.value) / logicalBytes * 100 : 0}%` }" /></div></div>
          <dl><div><dt>{{ t('warehouse.files') }}</dt><dd>{{ display(number(report.capacity, 'file_count')) }}</dd></div><div v-if="accountScope"><dt>{{ t('warehouse.totalUsers') }}</dt><dd>{{ display(number(report.capacity, 'total_users')) }}</dd></div><div v-if="accountScope"><dt>{{ t('warehouse.enabledUsers') }}</dt><dd>{{ display(number(report.capacity, 'enabled_users')) }}</dd></div><div><dt>{{ t('warehouse.activeTeams') }}</dt><dd>{{ display(number(report.usage, 'active_teams')) }}</dd></div><div v-for="item in [{ key: 'folder_count', label: t('warehouse.folders') }, { key: 'total_teams', label: t('warehouse.totalTeams') }, { key: 'member_count', label: t('warehouse.members') }, { key: 'active_shares', label: t('warehouse.activeShares') }]" :key="item.key"><dt>{{ item.label }}</dt><dd>{{ display(number(report.capacity, item.key)) }}</dd></div><div><dt>{{ t('warehouse.quota') }}</dt><dd>{{ number(report.capacity, 'quota_bytes') == null ? t('warehouse.noData') : formatSize(Number(report.capacity.quota_bytes)) }}</dd></div></dl>
        </section>
      </div>
      <section class="panel"><h2>{{ t('warehouse.usageDetails') }}</h2><dl class="usage-details"><div v-for="item in [{ key: 'registered_users', label: t('warehouse.newUsers') }, { key: 'operations', label: t('warehouse.operations') }, { key: 'shares_canceled', label: t('warehouse.sharesCanceled') }, { key: 'instant_uploads', label: t('warehouse.instantUploads') }, { key: 'shares_created', label: t('warehouse.sharesCreated') }, { key: 'share_accesses', label: t('warehouse.shareAccesses') }, { key: 'download_succeeded', label: t('warehouse.downloadSucceeded') }, { key: 'download_failed', label: t('warehouse.downloadFailed') }, { key: 'download_unknown', label: t('warehouse.downloadUnknown') }]" :key="item.key"><dt>{{ item.label }}</dt><dd>{{ display(number(report.usage, item.key)) }}</dd></div></dl><p class="footnote">{{ t('warehouse.downloadMeaning') }}</p></section>
      <section v-if="scope !== 'PERSONAL'" class="panel"><h2>{{ t('warehouse.teamDetails') }}</h2><div class="table-scroll"><table><thead><tr><th>{{ t('warehouse.team') }}</th><th>{{ t('warehouse.logicalBytes') }}</th><th>{{ t('warehouse.files') }}</th><th>{{ t('warehouse.members') }}</th><th>{{ t('warehouse.activeUsers') }}</th><th>{{ t('warehouse.uploads') }}</th><th>{{ t('warehouse.downloads') }}</th><th>{{ t('warehouse.date') }}</th></tr></thead><tbody><tr v-for="team in teamRows?.teams.records" :key="team.team_id"><td>{{ teamNames.get(team.team_id) || t('warehouse.teamIdentity', { id: team.team_id }) }}</td><td>{{ team.logical_bytes == null ? t('warehouse.noData') : formatSize(team.logical_bytes) }}</td><td>{{ display(team.file_count) }}</td><td>{{ display(team.member_count) }}</td><td>{{ display(team.active_users) }}</td><td>{{ display(team.uploads) }}</td><td>{{ display(team.download_attempts) }}</td><td>{{ team.sampled_day || t('warehouse.noData') }}</td></tr><tr v-if="!teamRows?.teams.records.length"><td colspan="8">{{ t('warehouse.noData') }}</td></tr></tbody></table></div><div class="pagination"><OButton size="sm" :disabled="page <= 1 || loading" @click="paginate(-1)">{{ t('warehouse.previous') }}</OButton><span>{{ page }} / {{ Math.max(1, teamRows?.teams.pages || 1) }}</span><OButton size="sm" :disabled="page >= (teamRows?.teams.pages || 1) || loading" @click="paginate(1)">{{ t('warehouse.next') }}</OButton></div></section>
      <section class="panel"><h2>{{ t('warehouse.runs') }}</h2><div class="table-scroll"><table><thead><tr><th>{{ t('warehouse.job') }}</th><th>{{ t('warehouse.status') }}</th><th>{{ t('warehouse.started') }}</th><th>{{ t('warehouse.updated') }}</th></tr></thead><tbody><tr v-for="run in runs" :key="run.run_id"><td>{{ t(`warehouse.jobs.${run.job}`) }}</td><td><span class="status" :class="run.status.toLowerCase()">{{ t(`warehouse.statuses.${run.status}`) }}</span></td><td>{{ dateTime(run.started_at) }}</td><td>{{ dateTime(run.updated_at) }}</td></tr><tr v-if="!runs.length"><td colspan="4">{{ t('warehouse.noData') }}</td></tr></tbody></table></div></section>
    </template>
  </div>
</template>

<style scoped>
.analytics { max-width: 1500px; margin: 0 auto; display: grid; gap: 20px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
h1 { margin: 0; font-size: 24px; letter-spacing: -.6px; font-weight: 650; }
.page-heading p { color: var(--text-secondary); font-size: 12px; margin: 8px 0 0; }
.filters { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; padding: 16px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); }
.presets { display: flex; gap: 5px; padding-bottom: 2px; }
label { display: grid; gap: 6px; font-size: 11px; color: var(--text-secondary); }
input, select { background: var(--bg-base); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; color: var(--text-primary); min-width: 110px; max-width: 230px; font: 12px var(--font-sans); }
input:focus-visible, select:focus-visible, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.notice { margin: 0; padding: 13px 16px; border: 1px solid var(--border); background: var(--bg-surface); border-radius: 8px; font-size: 13px; }
.danger, .failed { color: var(--danger); }
.text-button { color: var(--accent); background: transparent; border: none; font: inherit; cursor: pointer; text-decoration: underline; margin-left: 8px; }
.loading, .empty { padding: 70px 20px; text-align: center; color: var(--text-secondary); }
.empty h2 { font-size: 18px; }.empty p { font-size: 13px; }
.freshness { display: flex; gap: 18px; flex-wrap: wrap; color: var(--text-secondary); font: 11px var(--font-mono); }
.warning, .running { color: var(--warning); }
.headline-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-block: 1px solid var(--border); padding: 22px 0; }
.headline-metrics > div { padding: 0 22px; border-right: 1px solid var(--border); display: grid; align-content: start; gap: 9px; }
.headline-metrics > div:first-child { padding-left: 0; }.headline-metrics > div:last-child { border: 0; }
.headline-metrics span { color: var(--text-secondary); font-size: 12px; }.headline-metrics strong { font: 600 30px var(--font-mono); letter-spacing: -1px; }.headline-metrics small { font-size: 10px; color: var(--text-secondary); overflow-wrap: anywhere; }
.analysis-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, 1fr); gap: 20px; }
.panel { padding: 20px; border: 1px solid var(--border); background: var(--bg-surface); border-radius: 10px; min-width: 0; }
h2 { margin: 0 0 20px; font-size: 14px; }.capacity-item { margin-bottom: 18px; }.capacity-item > div:first-child { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; }.capacity-item span { color: var(--text-secondary); }.capacity-item strong { font: 12px var(--font-mono); }.bar-track { height: 5px; background: var(--border); border-radius: 4px; margin-top: 9px; overflow: hidden; }.bar-track span { display: block; height: 100%; background: var(--accent); }.bar-track .trash_bytes { opacity: .45; }.bar-track .version_bytes { opacity: .7; }
dl { margin: 18px 0 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }dt { color: var(--text-secondary); font-size: 11px; }dd { margin: 5px 0 0; font: 16px var(--font-mono); }.usage-details { grid-template-columns: repeat(6, minmax(0, 1fr)); }.footnote { color: var(--text-secondary); font-size: 11px; margin: 20px 0 0; line-height: 1.7; }
.table-scroll { overflow-x: auto; }table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; white-space: nowrap; }th { color: var(--text-secondary); font-weight: 500; font-size: 10px; }th, td { padding: 12px 10px; border-bottom: 1px solid var(--border); }td { font-variant-numeric: tabular-nums; }th:first-child, td:first-child { padding-left: 0; }.pagination { display: flex; justify-content: flex-end; align-items: center; gap: 14px; margin-top: 16px; font-size: 11px; color: var(--text-muted); }.status { font-size: 11px; }.succeeded { color: var(--accent); }
@media(max-width: 1050px) { .analysis-grid { grid-template-columns: 1fr; }.usage-details { grid-template-columns: repeat(3, 1fr); } }
@media(max-width: 600px) { .headline-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px 0; }.headline-metrics > div { padding: 0 14px; }.headline-metrics > div:nth-child(odd) { padding-left: 0; }.headline-metrics > div:nth-child(even) { border: 0; }.headline-metrics strong { font-size: 23px; }.filters { gap: 12px 8px; }.presets { width: 100%; }input, select { min-width: 100px; max-width: 100%; }.usage-details { grid-template-columns: repeat(2, minmax(0, 1fr)); }.panel { padding: 16px; }.freshness { gap: 8px; }.page-heading { align-items: start; } }
</style>
