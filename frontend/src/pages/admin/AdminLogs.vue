<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OInput from '@/components/base/OInput.vue'
import OButton from '@/components/base/OButton.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import type { OperationLog } from '@/api/admin'

const { formatDateFull } = useFormatters()
const logs = ref<OperationLog[]>([])
const loading = ref(false)
const userId = ref('')
const page = ref(1)
const total = ref(0)
const SIZE = 50

async function load() {
  loading.value = true
  try {
    const { data } = await adminApi.listLogs({
      userId: userId.value ? Number(userId.value) : undefined,
      page: page.value, size: SIZE
    })
    logs.value = data.data.records
    total.value = data.data.total
  } finally { loading.value = false }
}

function actionColor(action: string) {
  if (action.includes('DELETE') || action.includes('PURGE')) return 'var(--danger)'
  if (action.includes('UPLOAD') || action.includes('CREATE')) return 'var(--success)'
  if (action.includes('SHARE')) return 'var(--accent)'
  return 'var(--text-secondary)'
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <h2>操作日志</h2>
      <div class="logs-filter">
        <OInput v-model="userId" placeholder="用户ID筛选" class="logs-filter__input" type="number" />
        <OButton variant="primary" size="sm" @click="() => { page = 1; load() }">筛选</OButton>
        <OButton variant="subtle" size="sm" @click="() => { userId = ''; page = 1; load() }">重置</OButton>
      </div>
    </div>

    <div v-if="loading" style="display:flex;justify-content:center;padding:60px"><OSpinner /></div>
    <OEmptyState v-else-if="logs.length === 0" title="暂无日志" />
    <div v-else class="logs-timeline">
      <div v-for="log in logs" :key="log.id" class="log-item">
        <div class="log-item__dot" />
        <div class="log-item__content">
          <div class="log-item__header">
            <span class="log-item__action" :style="{ color: actionColor(log.actionType) }">{{ log.actionType }}</span>
            <span class="log-item__user">用户 {{ log.userId }}</span>
            <span class="log-item__time font-mono">{{ formatDateFull(log.createdAt) }}</span>
          </div>
          <div v-if="log.targetType || log.targetId" class="log-item__target">
            目标：{{ log.targetType || '未知' }}<span v-if="log.targetId"> #{{ log.targetId }}</span>
          </div>
          <div v-if="log.detail" class="log-item__detail">{{ log.detail }}</div>
        </div>
      </div>
    </div>

    <div v-if="total > SIZE" class="pagination">
      <OButton variant="ghost" size="sm" :disabled="page === 1" @click="() => { page--; load() }">上一页</OButton>
      <span class="pagination__info font-mono">{{ page }} / {{ Math.ceil(total / SIZE) }}</span>
      <OButton variant="ghost" size="sm" :disabled="page * SIZE >= total" @click="() => { page++; load() }">下一页</OButton>
    </div>
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
.logs-filter {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.logs-filter__input {
  width: 140px;
}
.logs-timeline { position: relative; padding-left: 20px; }
.logs-timeline::before {
  content: ''; position: absolute; left: 7px; top: 0; bottom: 0; width: 1px;
  background: var(--border);
}
.log-item { display: flex; gap: 12px; margin-bottom: 4px; position: relative; }
.log-item__dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--bg-overlay); border: 1px solid var(--border-hover);
  flex-shrink: 0; margin-top: 6px; position: relative; z-index: 1;
  margin-left: -20px;
}
.log-item__content {
  flex: 1; padding: 8px 10px; border-radius: 7px;
  background: var(--bg-elevated); border: 1px solid var(--border);
  margin-bottom: 4px;
}
.log-item__content:hover { border-color: var(--border-hover); }
.log-item__header { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.log-item__action { font-size: 12px; font-weight: 600; font-family: var(--font-mono); }
.log-item__user { font-size: 12px; color: var(--text-secondary); }
.log-item__time { font-size: 11px; color: var(--text-muted); margin-left: auto; }
.log-item__target { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
.log-item__detail { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.pagination { display: flex; align-items: center; gap: 12px; justify-content: center; margin-top: 20px; }
.pagination__info { font-size: 12px; color: var(--text-secondary); }

@media (max-width: 640px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }
  .logs-filter,
  .logs-filter__input {
    width: 100%;
  }
}
</style>
