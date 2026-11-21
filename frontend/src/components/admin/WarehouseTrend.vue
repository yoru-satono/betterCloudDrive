<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WarehouseDay } from '@/api/warehouse'
import { useFormatters } from '@/composables/useFormatters'

const props = defineProps<{ days: WarehouseDay[] }>()
const { t } = useI18n()
const { formatSize } = useFormatters()
const selected = ref<'active_users' | 'uploads' | 'download_attempts' | 'current_bytes'>('active_users')
const values = computed(() => props.days.map((day) => {
  const metrics = selected.value === 'current_bytes' ? day.capacity : day.usage
  return metrics == null ? null : Number(metrics[selected.value] ?? 0)
}))
const maximum = computed(() => Math.max(1, ...values.value.filter((value): value is number => value != null)))
const segments = computed(() => {
  const result: string[] = []
  let segment: string[] = []
  values.value.forEach((value, index) => {
    if (value == null) {
      if (segment.length) result.push(segment.join(' '))
      segment = []
    } else segment.push(`${20 + index * 760 / Math.max(1, values.value.length - 1)},${165 - value / maximum.value * 140}`)
  })
  if (segment.length) result.push(segment.join(' '))
  return result
})
function display(value: number | null) {
  if (value == null) return t('warehouse.noData')
  return selected.value === 'current_bytes' ? formatSize(value) : value.toLocaleString()
}
</script>

<template>
  <section class="trend">
    <div class="section-heading">
      <h2>{{ t('warehouse.trend') }}</h2>
      <select v-model="selected" :aria-label="t('warehouse.metric')">
        <option value="active_users">{{ t('warehouse.activeUsers') }}</option>
        <option value="uploads">{{ t('warehouse.uploads') }}</option>
        <option value="download_attempts">{{ t('warehouse.downloads') }}</option>
        <option value="current_bytes">{{ t('warehouse.currentBytes') }}</option>
      </select>
    </div>
    <div class="plot" role="img" :aria-label="t('warehouse.chartDescription')">
      <div class="plot-label">{{ display(maximum) }}</div>
      <svg viewBox="0 0 800 185" preserveAspectRatio="none" aria-hidden="true">
        <path d="M20 25H780 M20 95H780 M20 165H780" class="grid-line" />
        <polyline v-for="(segment, index) in segments" :key="index" :points="segment" class="series" />
        <template v-for="(value, index) in values" :key="index">
          <circle v-if="value != null" :cx="20 + index * 760 / Math.max(1, values.length - 1)" :cy="165 - value / maximum * 140" r="3" class="point">
            <title>{{ days[index]?.day }} · {{ display(value) }}</title>
          </circle>
        </template>
      </svg>
      <div class="plot-dates"><span>{{ days[0]?.day }}</span><span>{{ days.at(-1)?.day }}</span></div>
    </div>
    <details class="daily-details">
      <summary>{{ t('warehouse.dailyData') }}</summary>
      <div class="table-scroll"><table>
        <thead><tr><th>{{ t('warehouse.date') }}</th><th>{{ t('warehouse.value') }}</th></tr></thead>
        <tbody><tr v-for="(day, index) in days" :key="day.day"><td>{{ day.day }}</td><td>{{ display(values[index] ?? null) }}</td></tr></tbody>
      </table></div>
    </details>
  </section>
</template>

<style scoped>
.trend { border: 1px solid var(--border); background: var(--bg-surface); border-radius: 10px; padding: 20px; min-width: 0; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h2 { font-size: 14px; margin: 0; }
select { background: var(--bg-base); color: var(--text-primary); border: 1px solid var(--border); border-radius: 6px; padding: 7px 10px; max-width: 65%; }
.plot { margin-top: 20px; }
.plot-label, .plot-dates { font: 11px var(--font-mono); color: var(--text-secondary); }
svg { width: 100%; height: 195px; overflow: visible; }
.grid-line { stroke: var(--border); stroke-width: 1; fill: none; stroke-dasharray: 3 5; }
.series { fill: none; stroke: var(--accent); stroke-width: 2; vector-effect: non-scaling-stroke; }
.point { fill: var(--accent); }
.plot-dates { display: flex; justify-content: space-between; }
.daily-details { margin-top: 18px; font-size: 12px; color: var(--text-secondary); }
summary { cursor: pointer; padding: 4px 0; }
.table-scroll { max-height: 220px; overflow: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; }
th, td { padding: 8px; border-bottom: 1px solid var(--border); }
td:last-child, th:last-child { text-align: right; font-variant-numeric: tabular-nums; }
</style>
