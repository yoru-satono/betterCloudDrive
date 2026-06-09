<template>
  <div class="animate-fade-in" v-if="isAdmin">
    <h2>管理后台</h2>
    <!-- Stats -->
    <div class="stats-grid" style="margin:20px 0">
      <div class="stat-card card"><div class="stat-value">{{ stats.totalUsers }}</div><div class="stat-label">总用户</div></div>
      <div class="stat-card card"><div class="stat-value">{{ stats.activeUsers }}</div><div class="stat-label">活跃用户</div></div>
      <div class="stat-card card"><div class="stat-value text-mono">{{ formatSize(stats.totalStorageUsed) }}</div><div class="stat-label">总存储用量</div></div>
    </div>
    <!-- Users -->
    <h3 style="margin-bottom:12px">用户列表</h3>
    <div class="list-header"><span class="col-name">用户名</span><span class="col-role">角色</span><span class="col-status">状态</span><span class="col-actions"></span></div>
    <div v-for="u in users" :key="u.id" class="file-row">
      <span class="col-name">{{ u.username }}</span>
      <span class="col-role text-mono">{{ u.role === 'ROLE_ADMIN' ? '管理员' : '用户' }}</span>
      <span class="col-status"><span :class="['badge', u.status === 1 ? 'badge-on' : 'badge-off']">{{ u.status === 1 ? '正常' : '禁用' }}</span></span>
      <span class="col-actions"><button class="btn btn-ghost btn-sm" @click="toggleStatus(u)">{{ u.status === 1 ? '禁用' : '启用' }}</button></span>
    </div>
    <!-- Logs -->
    <h3 style="margin:24px 0 12px">操作日志</h3>
    <div class="list-header"><span class="col-name">用户ID</span><span class="col-action">动作</span><span class="col-date">时间</span></div>
    <div v-for="l in logs" :key="l.id" class="file-row">
      <span class="col-name text-mono">{{ l.userId }}</span>
      <span class="col-action"><span class="badge badge-on">{{ l.actionType }}</span></span>
      <span class="col-date text-muted">{{ formatDate(l.createdAt) }}</span>
    </div>
  </div>
  <div v-else class="empty-state"><p>需要管理员权限</p></div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import api from '@/api/client'
const auth = useAuthStore()
const isAdmin = computed(() => auth.isAdmin)
const stats = ref({ totalUsers: 0, activeUsers: 0, totalStorageUsed: 0 })
const users = ref<any[]>([]); const logs = ref<any[]>([])

async function fetch() {
  try {
    const [s, u, l] = await Promise.all([
      api.get('/admin/stats'), api.get('/admin/users'), api.get('/admin/logs', { params: { size: 20 } })
    ])
    stats.value = s.data.data; users.value = u.data.data.records; logs.value = l.data.data.records
  } catch { /* silently fail if not admin */ }
}
async function toggleStatus(u: any) {
  await api.patch(`/admin/users/${u.id}/status`, { status: u.status === 1 ? 0 : 1 })
  fetch()
}
function formatSize(b: number) { if (!b) return '0 B'; if (b < 1073741824) return (b/1048576).toFixed(1)+' MB'; return (b/1073741824).toFixed(2)+' GB' }
function formatDate(d: string) { return new Date(d).toLocaleString('zh-CN') }
onMounted(fetch)
</script>
<style scoped>
.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.stat-card { text-align: center; padding: 20px; }
.stat-value { font-size: 28px; font-weight: 700; font-family: var(--font-display); color: var(--accent); }
.stat-label { font-size: 12px; color: var(--text-muted); margin-top: 4px; text-transform: uppercase; }
.list-header { display: flex; padding: 8px 12px; border-bottom: 1px solid var(--border-default); font-size: 11px; text-transform: uppercase; color: var(--text-muted); }
.file-row { display: flex; align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--border-subtle); }
.col-name { flex: 1; }
.col-role { width: 80px; font-size: 12px; }
.col-status { width: 80px; }
.col-action { width: 100px; }
.col-date { width: 180px; }
.col-actions { width: 80px; text-align: right; }
.badge { padding: 2px 8px; border-radius: 20px; font-size: 11px; font-weight: 500; }
.badge-on { background: var(--accent-glow); color: var(--accent); }
.badge-off { background: var(--red-glow); color: var(--red); }
.empty-state { padding: 60px; text-align: center; color: var(--text-muted); }
</style>
