<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import * as adminApi from '@/api/admin'
import type { DeadLetter } from '@/api/admin'
import { useFormatters } from '@/composables/useFormatters'
import { i18n } from '@/i18n'

const { formatDateFull } = useFormatters()
const t = i18n.global.t
const records = ref<DeadLetter[]>([])
const selected = ref(new Set<string>())
const loading = ref(false)
const actionLoading = ref(false)
const page = ref(1)
const total = ref(0)
const size = 20
const queue = ref('')
const category = ref('')
const status = ref('OPEN')
const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))

async function load() {
  loading.value = true
  try {
    const response = await adminApi.listDeadLetters({
      queue: queue.value || undefined,
      category: category.value || undefined,
      status: status.value || undefined,
      page: page.value,
      size,
    })
    records.value = response.data.data.records
    total.value = response.data.data.total
    selected.value = new Set()
  } finally {
    loading.value = false
  }
}

function toggle(id: string) {
  const next = new Set(selected.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selected.value = next
}

async function replay(ids = [...selected.value]) {
  if (!ids.length || ids.length > 20) return
  if (!window.confirm(t('admin.deadLetter.confirmReplay', { count: ids.length }))) return
  actionLoading.value = true
  try {
    await adminApi.replayDeadLetters(ids)
    toast.success(t('admin.deadLetter.replayed'))
    await load()
  } finally {
    actionLoading.value = false
  }
}

async function discard(record: DeadLetter) {
  const reason = window.prompt(t('admin.deadLetter.discardPrompt'))
  if (!reason || reason.trim().length < 3) return
  actionLoading.value = true
  try {
    await adminApi.discardDeadLetter(record.id, reason.trim())
    toast.success(t('admin.deadLetter.discarded'))
    await load()
  } finally {
    actionLoading.value = false
  }
}

function changePage(next: number) {
  page.value = Math.min(Math.max(next, 1), pages.value)
  load()
}

onMounted(load)
</script>

<template>
  <section class="dead-letters">
    <header>
      <div>
        <h1>{{ t('admin.deadLetter.title') }}</h1>
        <p>{{ t('admin.deadLetter.subtitle') }}</p>
      </div>
      <OButton variant="primary" :disabled="selected.size === 0 || selected.size > 20" :loading="actionLoading" @click="replay()">
        {{ t('admin.deadLetter.replaySelected', { count: selected.size }) }}
      </OButton>
    </header>

    <div class="filters">
      <select v-model="queue" @change="page = 1; load()">
        <option value="">{{ t('admin.deadLetter.allSources') }}</option>
        <option value="notifications">{{ t('admin.deadLetter.notifications') }}</option>
        <option value="team-tasks">{{ t('admin.deadLetter.teamTasks') }}</option>
        <option value="unknown">{{ t('admin.deadLetter.unknown') }}</option>
      </select>
      <select v-model="category" @change="page = 1; load()">
        <option value="">{{ t('admin.deadLetter.allCategories') }}</option>
        <option value="invalid-payload">{{ t('admin.deadLetter.invalidPayload') }}</option>
        <option value="missing-record">{{ t('admin.deadLetter.missingRecord') }}</option>
        <option value="delivery-exhausted">{{ t('admin.deadLetter.deliveryExhausted') }}</option>
        <option value="unknown">{{ t('admin.deadLetter.unknown') }}</option>
      </select>
      <select v-model="status" @change="page = 1; load()">
        <option value="OPEN">{{ t('admin.deadLetter.open') }}</option>
        <option value="REPLAYED">{{ t('admin.deadLetter.replayedStatus') }}</option>
        <option value="DISCARDED">{{ t('admin.deadLetter.discardedStatus') }}</option>
        <option value="">{{ t('admin.deadLetter.allStatuses') }}</option>
      </select>
      <OButton @click="load">{{ t('admin.deadLetter.refresh') }}</OButton>
    </div>

    <OSpinner v-if="loading" />
    <OEmptyState v-else-if="records.length === 0" :title="t('admin.deadLetter.empty')" />
    <div v-else class="table-wrap">
      <table>
        <thead><tr><th></th><th>{{ t('admin.deadLetter.sourceType') }}</th><th>{{ t('admin.deadLetter.category') }}</th><th>{{ t('admin.deadLetter.deliveries') }}</th><th>{{ t('admin.deadLetter.correlation') }}</th><th>{{ t('admin.deadLetter.lastFailure') }}</th><th>{{ t('admin.deadLetter.status') }}</th><th>{{ t('admin.deadLetter.actions') }}</th></tr></thead>
        <tbody>
          <tr v-for="record in records" :key="record.id">
            <td><input type="checkbox" :checked="selected.has(record.id)" :disabled="record.status !== 'OPEN'" @change="toggle(record.id)" /></td>
            <td><strong>{{ record.originalQueue }}</strong><small>{{ record.taskType }}</small></td>
            <td>{{ record.failureCategory }}</td>
            <td>{{ record.deliveryCount }}</td>
            <td><code>{{ record.requestId || record.traceId || '—' }}</code></td>
            <td>{{ formatDateFull(record.lastFailedAt) }}</td>
            <td><span class="status" :class="`status--${record.status.toLowerCase()}`">{{ record.status }}</span></td>
            <td class="actions">
              <OButton size="sm" :disabled="record.status !== 'OPEN' || actionLoading" @click="replay([record.id])">{{ t('admin.deadLetter.replay') }}</OButton>
              <OButton size="sm" variant="danger" :disabled="record.status !== 'OPEN' || actionLoading" @click="discard(record)">{{ t('admin.deadLetter.discard') }}</OButton>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer v-if="total > size">
      <OButton size="sm" :disabled="page <= 1" @click="changePage(page - 1)">{{ t('admin.deadLetter.previous') }}</OButton>
      <span>{{ page }} / {{ pages }}（{{ total }}）</span>
      <OButton size="sm" :disabled="page >= pages" @click="changePage(page + 1)">{{ t('admin.deadLetter.next') }}</OButton>
    </footer>
  </section>
</template>

<style scoped>
.dead-letters { display: grid; gap: 18px; }
header { display: flex; align-items: start; justify-content: space-between; gap: 16px; }
h1 { margin: 0; font-size: 22px; }
p, small { color: var(--text-secondary); }
p { margin: 6px 0 0; font-size: 13px; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
select { background: var(--bg-surface); color: var(--text-primary); border: 1px solid var(--border); border-radius: 7px; padding: 7px 10px; }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 9px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 10px 12px; border-bottom: 1px solid var(--border); text-align: left; white-space: nowrap; }
th { color: var(--text-secondary); background: var(--bg-surface); font-weight: 500; }
td strong, td small { display: block; }
code { font-size: 11px; }
.actions, footer { display: flex; gap: 7px; align-items: center; }
.status { padding: 2px 7px; border-radius: 12px; font-size: 11px; background: var(--bg-elevated); }
.status--open { color: var(--danger); background: var(--danger-dim); }
.status--replayed { color: var(--success); }
.status--discarded { color: var(--text-muted); }
footer { justify-content: flex-end; color: var(--text-secondary); font-size: 12px; }
@media (max-width: 719px) { header { flex-direction: column; } }
</style>
