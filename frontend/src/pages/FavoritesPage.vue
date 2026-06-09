<template>
  <div class="animate-fade-in">
    <h2>收藏夹</h2>
    <div class="list-header"><span class="col-name">名称</span><span class="col-size">大小</span><span class="col-date">收藏日期</span><span class="col-actions"></span></div>
    <div v-if="items.length === 0" class="empty-state"><p>暂无收藏</p></div>
    <div v-for="f in items" :key="f.id" class="file-row">
      <span class="col-name truncate">{{ f.fileName }}</span>
      <span class="col-size text-mono text-muted">{{ formatSize(f.fileSize) }}</span>
      <span class="col-date text-muted">{{ formatDate(f.createdAt) }}</span>
      <span class="col-actions"><button class="btn btn-ghost btn-sm" style="color:var(--red)" @click="remove(f.id)">取消收藏</button></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '@/api/client'
const items = ref<any[]>([])
async function fetch() { const { data } = await api.get('/favorites'); items.value = data.data.records }
async function remove(fileId: number) { await api.delete(`/favorites/${fileId}`); fetch() }
function formatSize(b: number) { if (!b) return '—'; if (b < 1048576) return (b/1024).toFixed(1)+' KB'; return (b/1048576).toFixed(1)+' MB' }
function formatDate(d: string) { return new Date(d).toLocaleDateString('zh-CN') }
onMounted(fetch)
</script>
<style scoped>
.list-header { display: flex; padding: 8px 12px; border-bottom: 1px solid var(--border-default); font-size: 11px; text-transform: uppercase; color: var(--text-muted); margin-bottom: 4px; }
.file-row { display: flex; align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--border-subtle); }
.col-name { flex: 1; min-width: 0; }
.col-size { width: 90px; text-align: right; }
.col-date { width: 110px; text-align: right; }
.col-actions { width: 100px; text-align: right; }
.empty-state { padding: 60px; text-align: center; color: var(--text-muted); }
</style>
