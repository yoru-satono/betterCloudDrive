<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import type { AdminStats } from '@/api/admin'

const { formatSize } = useFormatters()
const stats = ref<AdminStats | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    const { data } = await adminApi.getStats()
    stats.value = data.data
  } finally { loading.value = false }
})

const cards = (s: AdminStats) => [
  { label: '总用户数',   value: s.totalUsers,      sub: `${s.activeUsers} 活跃` },
  { label: '总文件数',   value: s.totalFiles,       sub: '' },
  { label: '已用存储',   value: formatSize(s.totalStorageUsed), sub: `/ ${formatSize(s.totalStorageQuota)}` },
  { label: '活跃分享',   value: s.activeShares,     sub: '' },
  { label: '今日操作',   value: s.todayOperations,  sub: '' },
]
</script>

<template>
  <div class="page-enter">
    <h2 style="margin-bottom:24px">系统概览</h2>
    <div v-if="loading" style="display:flex;justify-content:center;padding:60px"><OSpinner /></div>
    <div v-else-if="stats" class="stats-grid">
      <div v-for="card in cards(stats)" :key="card.label" class="stat-card">
        <div class="stat-card__label">{{ card.label }}</div>
        <div class="stat-card__value">{{ card.value }}</div>
        <div v-if="card.sub" class="stat-card__sub">{{ card.sub }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.stat-card {
  padding: 20px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--bg-elevated); transition: border-color var(--fast);
}
.stat-card:hover { border-color: var(--border-hover); }
.stat-card__label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 8px; }
.stat-card__value { font-size: 28px; font-weight: 700; letter-spacing: -0.03em; color: var(--text-primary); }
.stat-card__sub { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
</style>
