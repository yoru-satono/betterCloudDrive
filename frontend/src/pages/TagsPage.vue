<template>
  <div class="animate-fade-in">
    <div class="page-header"><h2>标签管理</h2><button class="btn btn-primary btn-sm" @click="showCreate=true">新建标签</button></div>
    <div class="tag-grid">
      <div v-for="t in tags" :key="t.id" class="tag-card card" @click="selectedTag = t">
        <div class="tag-dot" :style="{ background: t.color }"></div>
        <span class="tag-name">{{ t.tagName }}</span>
        <button class="btn btn-ghost btn-sm" style="color:var(--red);margin-left:auto" @click.stop="delTag(t.id)">删除</button>
      </div>
    </div>
    <div v-if="selectedTag" class="tag-files" style="margin-top:24px">
      <h3 style="margin-bottom:12px"># {{ selectedTag.tagName }} 的文件</h3>
      <div v-for="f in tagFiles" :key="f.id" class="file-row">
        <span class="col-name truncate">{{ f.fileName }}</span>
        <span class="col-size text-mono text-muted">{{ formatSize(f.fileSize) }}</span>
      </div>
    </div>
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate=false">
      <div class="dialog animate-scale-in"><h3>新建标签</h3>
        <input v-model="newName" class="input" placeholder="标签名称" />
        <input v-model="newColor" class="input" placeholder="#1890ff" />
        <div class="dialog-actions"><button class="btn btn-ghost btn-sm" @click="showCreate=false">取消</button><button class="btn btn-primary btn-sm" @click="createTag">创建</button></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import api from '@/api/client'
const tags = ref<any[]>([]); const selectedTag = ref<any>(null); const tagFiles = ref<any[]>([])
const showCreate = ref(false); const newName = ref(''); const newColor = ref('#1890ff')
async function fetchTags() { const { data } = await api.get('/tags'); tags.value = data.data }
async function createTag() { await api.post('/tags', { tagName: newName.value, color: newColor.value }); showCreate.value = false; fetchTags() }
async function delTag(id: number) { await api.delete(`/tags/${id}`); fetchTags(); selectedTag.value = null }
async function fetchTagFiles() { if (!selectedTag.value) return; const { data } = await api.get(`/tags/${selectedTag.value.id}/files`); tagFiles.value = data.data.records }
function formatSize(b: number) { if (!b) return '—'; if (b < 1048576) return (b/1024).toFixed(1)+' KB'; return (b/1048576).toFixed(1)+' MB' }
watch(selectedTag, fetchTagFiles)
onMounted(fetchTags)
</script>
<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.tag-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.tag-card { display: flex; align-items: center; gap: 10px; cursor: pointer; transition: all var(--duration-fast); padding: 14px; }
.tag-card:hover { border-color: var(--border-active); }
.tag-dot { width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0; }
.tag-name { font-weight: 500; }
.file-row { display: flex; align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--border-subtle); }
.col-name { flex: 1; }
.col-size { width: 90px; text-align: right; }
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.dialog { background: var(--bg-elevated); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 24px; width: 360px; max-width: 90vw; display: flex; flex-direction: column; gap: 14px; }
.dialog h3 { font-size: 16px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
