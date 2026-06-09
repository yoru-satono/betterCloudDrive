<template>
  <div class="animate-fade-in">
    <div class="page-header">
      <h2>回收站</h2>
      <button class="btn btn-danger btn-sm" @click="emptyBin" :disabled="loading">清空回收站</button>
    </div>
    <p class="text-muted" style="margin-bottom:16px">文件删除后保留 30 天，之后自动清理</p>
    <div class="list-header"><span class="col-name">名称</span><span class="col-size">大小</span><span class="col-date">删除日期</span><span class="col-actions"></span></div>
    <div v-if="items.length === 0 && !loading" class="empty-state"><p>回收站为空</p></div>
    <div v-for="f in items" :key="f.id" class="file-row">
      <span class="col-name truncate">{{ f.fileName }}</span>
      <span class="col-size text-mono text-muted">{{ formatSize(f.fileSize) }}</span>
      <span class="col-date text-muted">{{ formatDate(f.deletedAt) }}</span>
      <span class="col-actions">
        <button class="btn btn-ghost btn-sm" @click="restore(f.id)">恢复</button>
        <button class="btn btn-ghost btn-sm" style="color:var(--red)" @click="permanentDel(f.id)">彻底删除</button>
      </span>
    </div>
    <div v-if="pages > 1" class="pagination">
      <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="fetch(page - 1)">上一页</button>
      <span class="text-muted">{{ page }} / {{ pages }}</span>
      <button class="btn btn-ghost btn-sm" :disabled="page >= pages" @click="fetch(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '@/api/client'
const items = ref<any[]>([])
const page = ref(1); const pages = ref(0); const loading = ref(false)
async function fetch(p = 1) {
  loading.value = true; page.value = p
  const { data } = await api.get('/recycle-bin', { params: { page: p, size: 20 } })
  items.value = data.data.records; pages.value = data.data.pages; loading.value = false
}
async function restore(id: number) { await api.post(`/recycle-bin/${id}/restore`); fetch() }
async function permanentDel(id: number) { if (!confirm('彻底删除后不可恢复，确定？')) return; await api.delete(`/recycle-bin/${id}`); fetch() }
async function emptyBin() { if (!confirm('确定清空回收站？')) return; await api.delete('/recycle-bin'); fetch() }
function formatSize(b: number) { if (!b) return '—'; if (b < 1048576) return (b/1024).toFixed(1)+' KB'; return (b/1048576).toFixed(1)+' MB' }
function formatDate(d: string) { return new Date(d).toLocaleDateString('zh-CN') }
onMounted(() => fetch())
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.list-header { display: flex; padding: 8px 12px; border-bottom: 1px solid var(--border-default); font-size: 11px; text-transform: uppercase; color: var(--text-muted); }
.file-row { display: flex; align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--border-subtle); }
.col-name { flex: 1; min-width: 0; }
.col-size { width: 90px; text-align: right; }
.col-date { width: 110px; text-align: right; }
.col-actions { width: 160px; text-align: right; display: flex; gap: 4px; justify-content: flex-end; }
.empty-state { padding: 60px 20px; text-align: center; color: var(--text-muted); }
.pagination { display: flex; justify-content: center; gap: 12px; margin-top: 20px; align-items: center; }
</style>
