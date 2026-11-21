<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { isAxiosError } from 'axios'
import { RefreshCw, ScanSearch, Square, X } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { resolveMessage } from '@/i18n'
import OButton from '@/components/base/OButton.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as inspection from '@/api/storage-inspections'

const { t, locale } = useI18n()
const { formatSize } = useFormatters()
const findingTypes = inspection.findingTypes
const runErrorKeys = new Set(['inspection_inventory_gap', 'inspection_time_budget_exceeded', 'inventory_list_unavailable',
  'inventory_head_unavailable', 'inventory_bucket_unavailable', 'inventory_response_invalid', 'inventory_cursor_invalid',
  'inspection_activity_unavailable', 'inspection_processing_failed'])
const overview = ref<inspection.InspectionOverview | null>(null)
const records = ref<inspection.InspectionFinding[]>([])
const history = ref<inspection.InspectionRun[]>([])
const selected = ref<inspection.FindingDetail | null>(null)
const selectedId = ref<string | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const busy = ref(false)
const refreshFailed = ref(false)
const actionError = ref('')
const loadedAt = ref<string | null>(null)
const total = ref(0)
const page = ref(1)
const type = ref<inspection.FindingType | ''>('')
const status = ref<inspection.InspectionFinding['status'] | ''>('')
const reviewStatus = ref<inspection.InspectionFinding['reviewStatus'] | ''>('')
const scope = ref<'PERSONAL' | 'TEAM' | ''>('')
const owner = ref('')
const appliedFilter = ref<inspection.FindingFilter>({})
const reviewAction = ref('NOTE')
const reviewReason = ref('')
const repairDialog = ref<HTMLDialogElement | null>(null)
const preview = ref<inspection.RepairPreview | null>(null)
const repairReason = ref('')
const repairConfirmed = ref(false)
const repairSubmission = ref<inspection.RepairRequest | null>(null)
let listController: AbortController | undefined
let detailController: AbortController | undefined
let listRevision = 0
let detailRevision = 0
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const pages = computed(() => Math.max(1, Math.ceil(total.value / 20)))
const activeRun = computed(() => {
  const run = overview.value?.latestRun
  return run && ['QUEUED', 'RUNNING'].includes(run.status) ? run : null
})
const canStart = computed(() => overview.value?.enabled && overview.value.accountingReady && !activeRun.value && !busy.value)
const canRepair = computed(() => selected.value?.finding.type === 'QUOTA_DRIFT'
  && selected.value.finding.status === 'CONFIRMED' && overview.value?.accountingReady)
const suspected = computed(() => count('SUSPECTED'))
const confirmed = computed(() => count('CONFIRMED'))

function count(value: inspection.InspectionFinding['status']) {
  return overview.value?.counts.filter((entry) => entry.status === value).reduce((sum, entry) => sum + Number(entry.count), 0) ?? 0
}
function number(value: number | null | undefined) { return value == null ? t('inspection.noData') : value.toLocaleString(locale.value) }
function bytes(value: number | null | undefined) {
  return value == null ? t('inspection.noData') : `${value < 0 ? '−' : ''}${formatSize(Math.abs(value))}`
}
function time(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString(locale.value, { timeZone: overview.value?.zone || 'Asia/Shanghai' }) : t('inspection.noData')
}
function ownerLabel(finding: inspection.InspectionFinding) {
  return finding.scope ? t(`inspection.scope.${finding.scope}`, { id: finding.ownerId }) : t('inspection.unassigned')
}
function subject(finding: inspection.InspectionFinding) {
  return finding.objectFingerprint?.slice(0, 12) || `${t(`inspection.reference.${finding.referenceKind}`)} #${finding.referenceId}`
}
function errorText(error: unknown) {
  if (isAxiosError(error) && typeof error.response?.data?.messageKey === 'string') {
    return resolveMessage(error.response.data.messageKey, error.response.data.messageParams)
  }
  return t('inspection.actionFailed')
}
function runError(key: string) { return t(runErrorKeys.has(key) ? `inspection.runError.${key}` : 'inspection.runIncomplete') }

function schedule() {
  clearTimeout(timer)
  if (!disposed && activeRun.value) timer = setTimeout(() => void load(true), 3000)
}

async function load(silent = false) {
  if (disposed || (silent && loading.value)) return
  listController?.abort()
  listController = new AbortController()
  const current = ++listRevision
  loading.value = true
  try {
    const [summary, findings, runs] = await Promise.all([
      inspection.overview(listController.signal), inspection.findings(appliedFilter.value, page.value, listController.signal),
      inspection.runs(1, 5, listController.signal),
    ])
    if (disposed || current !== listRevision) return
    overview.value = summary.data.data
    records.value = findings.data.data.records
    total.value = findings.data.data.total
    history.value = runs.data.data.records
    loadedAt.value = new Date().toISOString()
    refreshFailed.value = false
    if (selectedId.value) await loadDetail(selectedId.value, true)
  } catch {
    if (!disposed && current === listRevision && !listController.signal.aborted) refreshFailed.value = true
  } finally {
    if (!disposed && current === listRevision) { loading.value = false; schedule() }
  }
}

async function loadDetail(id: string, retain = false) {
  detailController?.abort()
  detailController = new AbortController()
  const current = ++detailRevision
  selectedId.value = id
  detailLoading.value = true
  if (!retain) { selected.value = null; reviewReason.value = ''; actionError.value = '' }
  try {
    const response = await inspection.detail(id, detailController.signal)
    if (!disposed && current === detailRevision) selected.value = response.data.data
  } catch (error) {
    if (!disposed && current === detailRevision && !detailController.signal.aborted) actionError.value = errorText(error)
  } finally {
    if (!disposed && current === detailRevision) detailLoading.value = false
  }
}

function applyFilters() {
  const id = owner.value.trim() ? Number(owner.value) : undefined
  if (id != null && (!scope.value || !Number.isSafeInteger(id) || id < 1)) {
    actionError.value = t('error.inspectionInvalidFilter'); return
  }
  appliedFilter.value = { type: type.value || undefined, status: status.value || undefined, reviewStatus: reviewStatus.value || undefined,
    scope: scope.value || undefined, ownerId: id }
  actionError.value = ''
  page.value = 1
  void load()
}
function changePage(delta: number) { page.value += delta; void load() }

function closeDetail() {
  detailRevision++; detailController?.abort()
  selectedId.value = null; selected.value = null; detailLoading.value = false
}

async function action(operation: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try { await operation(); await load() }
  catch (error) { actionError.value = errorText(error); await load() }
  finally { busy.value = false }
}
async function saveReview() {
  if (!selected.value || !reviewReason.value.trim()) return
  const id = selected.value.finding.id
  await action(async () => {
    await inspection.review(id, reviewAction.value, reviewReason.value.trim())
    reviewReason.value = ''
  })
}
async function showPreview() {
  if (!selected.value) return
  await action(async () => {
    const response = await inspection.preview(selected.value!.finding.id)
    preview.value = response.data.data
    repairReason.value = ''
    repairConfirmed.value = false
    repairSubmission.value = null
    await nextTick()
    if (!repairDialog.value?.open) repairDialog.value?.showModal()
  })
}
function closePreview() {
  if (busy.value) return
  repairDialog.value?.close()
  preview.value = null
  repairSubmission.value = null
}
async function applyRepair() {
  if (!preview.value || !repairConfirmed.value || !repairReason.value.trim()) return
  repairSubmission.value ||= { previewId: preview.value.id, idempotencyKey: crypto.randomUUID(), reason: repairReason.value.trim() }
  await action(async () => {
    await inspection.repair(repairSubmission.value!)
    repairDialog.value?.close()
    preview.value = null
    repairSubmission.value = null
  })
}

onMounted(() => void load())
onBeforeUnmount(() => {
  disposed = true; listRevision++; detailRevision++
  clearTimeout(timer); listController?.abort(); detailController?.abort(); repairDialog.value?.close()
})
</script>

<template>
  <section class="inspections" :aria-label="t('inspection.title')">
    <header class="inspection-header">
      <div><p class="inspection-eyebrow">{{ t('inspection.eyebrow') }}</p><h1>{{ t('inspection.title') }}</h1>
        <p class="inspection-subtitle">{{ t('inspection.subtitle') }}</p></div>
      <div class="inspection-actions">
        <OButton type="button" :disabled="loading" @click="load()"><RefreshCw :size="14" />{{ t('inspection.refresh') }}</OButton>
        <OButton type="button" variant="primary" :disabled="!canStart" :loading="busy" @click="action(() => inspection.start())">
          <ScanSearch :size="15" />{{ t('inspection.start') }}</OButton>
      </div>
    </header>

    <div v-if="refreshFailed" class="inspection-notice inspection-notice--error" role="alert">
      <span>{{ t('inspection.refreshFailed') }} <template v-if="loadedAt">{{ t('inspection.retained', { time: time(loadedAt) }) }}</template></span>
      <OButton type="button" size="sm" @click="load()">{{ t('inspection.retry') }}</OButton>
    </div>
    <p v-if="actionError && !preview" class="inspection-notice inspection-notice--error" role="alert">{{ actionError }}</p>
    <p v-if="overview && !overview.accountingReady" class="inspection-notice" role="status">{{ t('inspection.cutoverRequired') }}</p>
    <p v-else-if="overview && !overview.enabled" class="inspection-notice" role="status">{{ t('inspection.disabled') }}</p>

    <div class="inspection-summary" aria-live="polite">
      <div class="inspection-summary__coverage">
        <span>{{ t('inspection.lastComplete') }}</span>
        <strong>{{ overview?.latestComplete ? time(overview.latestComplete.finishedAt) : t('inspection.noComplete') }}</strong>
        <small>{{ overview?.nextScheduledAt ? t('inspection.nextSchedule', { time: time(overview.nextScheduledAt) }) : t('inspection.manualSchedule') }}</small>
      </div>
      <div><span>{{ t('inspection.suspected') }}</span><strong>{{ overview ? number(suspected) : t('inspection.noData') }}</strong></div>
      <div><span>{{ t('inspection.confirmed') }}</span><strong class="inspection-accent">{{ overview ? number(confirmed) : t('inspection.noData') }}</strong></div>
      <div><span>{{ t('inspection.objectCount') }}</span><strong>{{ number(overview?.latestComplete?.objectsScanned) }}</strong>
        <small>{{ bytes(overview?.latestComplete?.objectBytes) }}</small></div>
    </div>

    <div v-if="activeRun" class="inspection-running" role="status">
      <div class="inspection-running__label"><span class="inspection-pulse" /><strong>{{ t(`inspection.runStatus.${activeRun.status}`) }}</strong>
        <span>{{ t(`inspection.phase.${activeRun.phase}`) }}</span></div>
      <span>{{ t('inspection.progress', { objects: number(activeRun.objectsScanned), references: number(activeRun.referencesScanned) }) }}</span>
      <OButton type="button" size="sm" :disabled="busy || activeRun.cancelRequested" @click="action(() => inspection.cancel(activeRun!.id))">
        <Square :size="12" />{{ activeRun.cancelRequested ? t('inspection.canceling') : t('inspection.cancelRun') }}</OButton>
    </div>

    <form class="inspection-filters" @submit.prevent="applyFilters">
      <label>{{ t('inspection.type') }}<select v-model="type"><option value="">{{ t('inspection.allTypes') }}</option>
        <option v-for="item in findingTypes" :key="item" :value="item">{{ t(`inspection.typeLabel.${item}`) }}</option></select></label>
      <label>{{ t('inspection.status') }}<select v-model="status"><option value="">{{ t('inspection.allStatuses') }}</option>
        <option v-for="item in ['SUSPECTED', 'CONFIRMED', 'RESOLVED']" :key="item" :value="item">{{ t(`inspection.findingStatus.${item}`) }}</option></select></label>
      <label>{{ t('inspection.reviewStatus') }}<select v-model="reviewStatus"><option value="">{{ t('inspection.allReviews') }}</option>
        <option v-for="item in ['OPEN', 'ACKNOWLEDGED', 'IGNORED']" :key="item" :value="item">{{ t(`inspection.reviewLabel.${item}`) }}</option></select></label>
      <label>{{ t('inspection.space') }}<select v-model="scope"><option value="">{{ t('inspection.allSpaces') }}</option>
        <option value="PERSONAL">{{ t('inspection.personal') }}</option><option value="TEAM">{{ t('inspection.team') }}</option></select></label>
      <label class="inspection-owner">{{ t('inspection.ownerId') }}<input v-model="owner" inputmode="numeric" :placeholder="t('inspection.optional')" /></label>
      <OButton type="submit" :disabled="loading">{{ t('inspection.applyFilters') }}</OButton>
    </form>

    <div class="inspection-workspace">
      <section class="inspection-list" :aria-label="t('inspection.findings')" :aria-busy="loading">
        <div class="inspection-section-title"><h2>{{ t('inspection.findings') }}</h2><span>{{ t('inspection.total', { count: number(total) }) }}</span></div>
        <p v-if="loading && !overview" class="inspection-empty" role="status">{{ t('inspection.loading') }}</p>
        <p v-else-if="!records.length" class="inspection-empty">{{ refreshFailed ? t('inspection.unavailable') : t('inspection.noFindings') }}</p>
        <div v-else class="inspection-table-wrap"><table class="inspection-table">
          <thead><tr><th>{{ t('inspection.type') }}</th><th>{{ t('inspection.space') }}</th><th>{{ t('inspection.status') }}</th>
            <th>{{ t('inspection.lastObserved') }}</th><th><span class="inspection-sr-only">{{ t('inspection.details') }}</span></th></tr></thead>
          <tbody><tr v-for="finding in records" :key="finding.id" :class="{ 'inspection-row--selected': selectedId === finding.id }">
            <td><strong>{{ t(`inspection.typeLabel.${finding.type}`) }}</strong><code>{{ subject(finding) }}</code></td>
            <td>{{ ownerLabel(finding) }}</td>
            <td><span class="inspection-badge" :data-status="finding.status">{{ t(`inspection.findingStatus.${finding.status}`) }}</span>
              <small>{{ t(`inspection.reviewLabel.${finding.reviewStatus}`) }}</small></td>
            <td class="inspection-date">{{ time(finding.lastObservedAt) }}</td>
            <td><OButton type="button" size="sm" :aria-label="t('inspection.viewFinding', { type: t(`inspection.typeLabel.${finding.type}`), subject: subject(finding) })"
              @click="loadDetail(finding.id)">{{ t('inspection.details') }}</OButton></td>
          </tr></tbody>
        </table></div>
        <footer class="inspection-pagination"><span>{{ t('inspection.page', { page, pages }) }}</span><div>
          <OButton type="button" size="sm" :disabled="page <= 1 || loading" @click="changePage(-1)">{{ t('inspection.previous') }}</OButton>
          <OButton type="button" size="sm" :disabled="page >= pages || loading" @click="changePage(1)">{{ t('inspection.next') }}</OButton></div></footer>
      </section>

      <aside class="inspection-detail" :aria-label="t('inspection.evidence')" :aria-busy="detailLoading">
        <div class="inspection-section-title"><h2>{{ t('inspection.evidence') }}</h2>
          <OButton v-if="selectedId" type="button" size="sm" icon :aria-label="t('inspection.close')" @click="closeDetail"><X :size="14" /></OButton></div>
        <p v-if="detailLoading && !selected" class="inspection-empty" role="status">{{ t('inspection.loading') }}</p>
        <p v-else-if="!selected" class="inspection-empty">{{ t('inspection.selectFinding') }}</p>
        <template v-else>
          <h3>{{ t(`inspection.typeLabel.${selected.finding.type}`) }}</h3>
          <p class="inspection-detail__scope">{{ ownerLabel(selected.finding) }} · {{ t(`inspection.findingStatus.${selected.finding.status}`) }}</p>
          <p v-if="selected.finding.metadataReason" class="inspection-explanation">{{ t(`inspection.metadataReason.${selected.finding.metadataReason}`) }}</p>
          <code v-if="selected.objectKey" class="inspection-object-key">{{ selected.objectKey }}</code>
          <dl class="inspection-facts"><div><dt>{{ t('inspection.expectedBytes') }}</dt><dd>{{ bytes(selected.finding.expectedBytes) }}</dd></div>
            <div><dt>{{ t('inspection.observedBytes') }}</dt><dd>{{ bytes(selected.finding.observedBytes) }}</dd></div>
            <div><dt>{{ t('inspection.firstSeen') }}</dt><dd>{{ time(selected.finding.firstSeenAt) }}</dd></div>
            <div><dt>{{ t('inspection.lastObserved') }}</dt><dd>{{ time(selected.finding.lastObservedAt) }}</dd></div>
            <div><dt>{{ t('inspection.observations') }}</dt><dd>{{ number(selected.finding.observations) }}</dd></div>
            <div v-if="selected.finding.objectModifiedAt"><dt>{{ t('inspection.objectModified') }}</dt><dd>{{ time(selected.finding.objectModifiedAt) }}</dd></div>
            <div v-if="selected.finding.resolvedAt"><dt>{{ t('inspection.resolvedAt') }}</dt><dd>{{ time(selected.finding.resolvedAt) }}</dd></div>
          </dl>
          <p v-if="selected.finding.status === 'SUSPECTED'" class="inspection-explanation">{{ t('inspection.observationWindow') }}</p>
          <div class="inspection-detail__actions">
            <OButton type="button" :disabled="!canStart" @click="action(() => inspection.recheck(selected!.finding.id))">{{ t('inspection.recheck') }}</OButton>
            <OButton v-if="canRepair" type="button" variant="danger" :disabled="busy" @click="showPreview">{{ t('inspection.previewRepair') }}</OButton>
          </div>
          <form class="inspection-review-form" @submit.prevent="saveReview">
            <label>{{ t('inspection.recordAction') }}<select v-model="reviewAction"><option value="NOTE">{{ t('inspection.note') }}</option>
              <option value="ACKNOWLEDGE">{{ t('inspection.acknowledge') }}</option><option value="IGNORE">{{ t('inspection.ignore') }}</option></select></label>
            <label>{{ t('inspection.reason') }}<textarea v-model="reviewReason" rows="3" maxlength="500" required /></label>
            <OButton type="submit" :disabled="busy || !reviewReason.trim()">{{ t('inspection.saveRecord') }}</OButton>
          </form>
          <ol v-if="selected.reviews.length || selected.repairs.length" class="inspection-audit">
            <li v-for="repair in selected.repairs" :key="repair.id"><strong>{{ t('inspection.repairRecorded') }}</strong>
              <span>{{ bytes(repair.usedBefore) }} → {{ bytes(repair.usedAfter) }}</span><p>{{ repair.reason }}</p>
              <small>{{ time(repair.createdAt) }} · {{ t('inspection.actor', { id: repair.actorId }) }}</small></li>
            <li v-for="review in selected.reviews" :key="review.id"><strong>{{ t(`inspection.reviewAction.${review.action}`) }}</strong><p>{{ review.reason }}</p>
              <small>{{ time(review.createdAt) }} · {{ t('inspection.actor', { id: review.actorId }) }}</small></li>
          </ol>
        </template>
      </aside>
    </div>

    <section class="inspection-history" :aria-label="t('inspection.runHistory')">
      <div class="inspection-section-title"><h2>{{ t('inspection.runHistory') }}</h2><span>{{ t('inspection.latestRuns') }}</span></div>
      <p v-if="!history.length" class="inspection-empty">{{ t('inspection.noRuns') }}</p>
      <div v-else class="inspection-table-wrap"><table class="inspection-table"><thead><tr><th>{{ t('inspection.runKind') }}</th><th>{{ t('inspection.status') }}</th>
        <th>{{ t('inspection.startedAt') }}</th><th>{{ t('inspection.coverage') }}</th><th>{{ t('inspection.objectCount') }}</th></tr></thead>
        <tbody><tr v-for="run in history" :key="run.id"><td>{{ t(`inspection.kind.${run.kind}`) }}</td><td>
          <span class="inspection-badge" :data-status="run.status">{{ t(`inspection.runStatus.${run.status}`) }}</span>
          <small v-if="run.errorKey" class="inspection-run-error">{{ runError(run.errorKey) }}</small></td><td class="inspection-date">{{ time(run.startedAt) }}</td>
          <td><template v-if="run.kind === 'FULL'">{{ run.metadataComplete ? t('inspection.metadataComplete') : t('inspection.metadataIncomplete') }}<small>
            {{ run.inventoryComplete ? t('inspection.inventoryComplete') : t('inspection.inventoryIncomplete') }}</small></template><template v-else>{{ t('inspection.singleFinding') }}</template></td>
          <td>{{ run.kind === 'FULL' ? number(run.objectsScanned) : t('inspection.noData') }}</td></tr></tbody></table></div>
    </section>

    <dialog ref="repairDialog" class="inspection-repair-dialog" aria-labelledby="inspection-repair-title" @cancel.prevent="closePreview">
      <form v-if="preview" @submit.prevent="applyRepair">
        <div class="inspection-section-title"><h2 id="inspection-repair-title">{{ t('inspection.repairTitle') }}</h2>
          <OButton type="button" icon :disabled="busy" :aria-label="t('inspection.close')" @click="closePreview"><X :size="16" /></OButton></div>
        <p>{{ t('inspection.repairScope', { id: preview.ownerId, scope: t(`inspection.spaceName.${preview.scope}`) }) }}</p>
        <dl class="inspection-repair-values"><div><dt>{{ t('inspection.currentUsage') }}</dt><dd>{{ bytes(preview.recordedBytes) }}</dd></div>
          <div><dt>{{ t('inspection.recomputedUsage') }}</dt><dd>{{ bytes(preview.expectedBytes) }}</dd></div>
          <div><dt>{{ t('inspection.adjustment') }}</dt><dd>{{ preview.expectedBytes >= preview.recordedBytes ? '+' : '−' }}{{ bytes(Math.abs(preview.expectedBytes - preview.recordedBytes)) }}</dd></div></dl>
        <p class="inspection-explanation">{{ t('inspection.previewExpires', { time: time(preview.expiresAt) }) }}</p>
        <p v-if="actionError" class="inspection-notice inspection-notice--error" role="alert">{{ actionError }}</p>
        <label>{{ t('inspection.reason') }}<textarea v-model="repairReason" :disabled="!!repairSubmission" rows="3" maxlength="500" required /></label>
        <label class="inspection-confirm"><input v-model="repairConfirmed" type="checkbox" />{{ t('inspection.confirmRepair') }}</label>
        <footer><OButton type="button" :disabled="busy" @click="closePreview">{{ t('inspection.close') }}</OButton>
          <OButton type="button" :disabled="busy" @click="showPreview">{{ t('inspection.refreshPreview') }}</OButton>
          <OButton type="submit" variant="danger" :loading="busy" :disabled="!repairConfirmed || !repairReason.trim()">{{ t('inspection.applyRepair') }}</OButton></footer>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.inspections { max-width: 1600px; margin: 0 auto; color: var(--text-primary); }
.inspection-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.inspection-header h1 { font-size: 25px; font-weight: 650; letter-spacing: -.6px; margin: 4px 0 8px; }
.inspection-eyebrow { color: var(--accent); font-size: 10px; font-weight: 700; letter-spacing: 1.6px; margin: 0; }
.inspection-subtitle, .inspection-detail__scope { color: var(--text-secondary); font-size: 12px; margin: 0; line-height: 1.6; }
.inspection-actions, .inspection-detail__actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.inspection-notice { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; background: var(--bg-elevated); border-left: 3px solid var(--accent); font-size: 12px; line-height: 1.7; margin: 0 0 16px; }
.inspection-notice--error { border-left-color: var(--danger); }
.inspection-summary { display: grid; grid-template-columns: 2fr 1fr 1fr 1.2fr; background: var(--bg-surface); border: 1px solid var(--border); margin-bottom: 16px; }
.inspection-summary > div { padding: 18px 22px; min-width: 0; border-left: 1px solid var(--border); }
.inspection-summary > div:first-child { border-left: 0; }
.inspection-summary span, .inspection-summary small { display: block; color: var(--text-secondary); font-size: 11px; line-height: 1.6; }
.inspection-summary strong { display: block; font-size: 25px; margin: 6px 0 0; font-variant-numeric: tabular-nums; font-weight: 600; }
.inspection-summary__coverage strong { font-size: 15px; line-height: 1.8; }
.inspection-summary .inspection-accent { color: var(--danger); }
.inspection-running { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding: 11px 14px; border: 1px solid var(--border); margin-bottom: 16px; font-size: 12px; }
.inspection-running > span { color: var(--text-secondary); flex: 1; }
.inspection-running__label { display: flex; align-items: center; gap: 8px; }
.inspection-pulse { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: inspection-pulse 1.8s infinite; }
@keyframes inspection-pulse { 50% { opacity: .35; } }
.inspection-filters { display: flex; align-items: end; gap: 10px; flex-wrap: wrap; margin: 20px 0 16px; }
.inspections label { display: flex; flex-direction: column; gap: 6px; color: var(--text-secondary); font-size: 11px; min-width: 0; }
.inspections input:not([type=checkbox]), .inspections select, .inspections textarea { color: var(--text-primary); background: var(--bg-surface); border: 1px solid var(--border); border-radius: 5px; padding: 8px 10px; font-family: inherit; font-size: 12px; min-width: 0; max-width: 100%; }
.inspections textarea { resize: vertical; line-height: 1.7; }
.inspections input:focus-visible, .inspections select:focus-visible, .inspections textarea:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.inspection-owner { width: 115px; }
.inspection-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 18px; align-items: start; }
.inspection-list, .inspection-detail, .inspection-history { min-width: 0; border: 1px solid var(--border); background: var(--bg-surface); }
.inspection-section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 15px 18px; border-bottom: 1px solid var(--border); }
.inspection-section-title h2 { font-size: 13px; font-weight: 600; margin: 0; }
.inspection-section-title > span { font-size: 11px; color: var(--text-secondary); }
.inspection-table-wrap { overflow-x: auto; }
.inspection-table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
.inspection-table th { position: relative; background: var(--bg-elevated); color: var(--text-secondary); font-size: 10px; font-weight: 500; white-space: nowrap; padding: 10px 14px; }
.inspection-table td { padding: 13px 14px; border-bottom: 1px solid var(--border); vertical-align: middle; }
.inspection-table strong { display: block; font-size: 12px; font-weight: 500; white-space: nowrap; }
.inspection-table code, .inspection-table small { display: block; margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.inspection-row--selected td { background: var(--accent-dim); }
.inspection-date { font-size: 11px; font-variant-numeric: tabular-nums; line-height: 1.6; min-width: 100px; }
.inspection-badge { display: inline-flex; border: 1px solid var(--border); border-radius: 4px; padding: 3px 6px; font-size: 10px; white-space: nowrap; }
.inspection-badge[data-status=CONFIRMED], .inspection-badge[data-status=FAILED] { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 35%, transparent); }
.inspection-badge[data-status=SUCCEEDED], .inspection-badge[data-status=RESOLVED] { color: var(--accent); }
.inspection-pagination { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; color: var(--text-secondary); font-size: 11px; }
.inspection-pagination > div { display: flex; gap: 6px; }
.inspection-empty { padding: 35px 18px; font-size: 12px; color: var(--text-secondary); text-align: center; line-height: 1.8; }
.inspection-detail h3 { margin: 18px 18px 5px; font-size: 15px; font-weight: 600; }
.inspection-detail__scope { margin: 0 18px 14px; }
.inspection-object-key { display: block; margin: 0 18px 14px; padding: 10px; background: var(--bg-elevated); border-radius: 4px; overflow-wrap: anywhere; font-size: 11px; line-height: 1.8; }
.inspection-facts { display: grid; gap: 12px; margin: 0; padding: 0 18px 16px; }
.inspection-facts > div { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; font-size: 11px; }
.inspection-facts dt { color: var(--text-secondary); flex-shrink: 0; }
.inspection-facts dd { margin: 0; font-variant-numeric: tabular-nums; text-align: right; overflow-wrap: anywhere; }
.inspection-explanation { color: var(--text-secondary); font-size: 11px; line-height: 1.8; margin: 0 18px 14px; }
.inspection-detail__actions { padding: 0 18px 16px; }
.inspection-review-form { display: grid; gap: 12px; padding: 16px 18px; border-top: 1px solid var(--border); }
.inspection-review-form .o-btn { justify-self: start; }
.inspection-audit { list-style: none; margin: 0; padding: 0 18px 18px; }
.inspection-audit li { display: grid; gap: 5px; border-top: 1px solid var(--border); padding: 12px 0; font-size: 11px; }
.inspection-audit strong { font-weight: 500; }.inspection-audit p { margin: 0; line-height: 1.7; overflow-wrap: anywhere; white-space: pre-wrap; }
.inspection-audit small { color: var(--text-secondary); line-height: 1.7; }
.inspection-history { margin-top: 20px; }
.inspection-run-error { max-width: 260px; white-space: normal; }
.inspection-repair-dialog { width: min(480px, calc(100vw - 32px)); padding: 0; color: var(--text-primary); background: var(--bg-surface); border: 1px solid var(--border); border-radius: 10px; max-height: 90vh; overflow-y: auto; }
.inspection-repair-dialog::backdrop { background: var(--backdrop); backdrop-filter: blur(4px); }
.inspection-repair-dialog form > p, .inspection-repair-dialog form > label { margin: 16px 20px; font-size: 12px; line-height: 1.8; }
.inspection-repair-values { margin: 0 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 15px; padding: 16px 0; border-bottom: 1px solid var(--border); }
.inspection-repair-values dt { font-size: 11px; color: var(--text-secondary); }.inspection-repair-values dd { margin: 6px 0 0; font-size: 22px; font-variant-numeric: tabular-nums; }
.inspection-repair-values > div:last-child { grid-column: 1 / -1; }.inspection-repair-values > div:last-child dd { font-size: 15px; }
.inspections .inspection-confirm { flex-direction: row; align-items: flex-start; gap: 8px; }.inspection-confirm input { margin-top: 5px; accent-color: var(--accent); }
.inspection-repair-dialog footer { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; padding: 16px 20px; border-top: 1px solid var(--border); }
.inspection-sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 1100px) { .inspection-workspace { grid-template-columns: minmax(0, 1fr); }.inspection-detail { width: 100%; }.inspection-detail .inspection-facts { grid-template-columns: 1fr 1fr; }.inspection-summary > div { padding: 16px; } }
@media (max-width: 600px) {
  .inspection-header { align-items: flex-start; flex-direction: column; gap: 14px; }.inspection-header h1 { font-size: 22px; }
  .inspection-actions { width: 100%; }.inspection-summary { grid-template-columns: 1fr 1fr; }.inspection-summary__coverage { grid-column: 1 / -1; }
  .inspection-summary > div { border-bottom: 1px solid var(--border); }.inspection-summary > div:nth-child(2) { border-left: 0; }.inspection-summary > div:last-child { grid-column: 1 / -1; border-left: 0; border-bottom: 0; }
  .inspection-filters > label { flex: 1 1 calc(50% - 10px); }.inspection-filters > .inspection-owner { flex-basis: 110px; }
  .inspection-detail .inspection-facts { grid-template-columns: 1fr; }.inspection-notice { align-items: flex-start; flex-wrap: wrap; }.inspection-running > span { flex-basis: 100%; }
}
@media (prefers-reduced-motion: reduce) { .inspection-pulse { animation: none; } }
</style>
