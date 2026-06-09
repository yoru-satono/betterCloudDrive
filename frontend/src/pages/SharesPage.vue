<template>
  <div class="animate-fade-in">
    <div class="page-header">
      <h2>我的分享</h2>
      <button class="btn btn-primary btn-sm" @click="showCreate = true">创建分享</button>
    </div>
    <div v-if="shares.length === 0" class="empty-state"><p>暂无分享链接</p></div>
    <div v-for="s in shares" :key="s.id" class="share-card card">
      <div class="share-info">
        <div class="share-code text-mono">{{ s.shareCode }}</div>
        <div class="share-meta text-muted">
          {{ s.passwordHash ? '🔒 有密码' : '🌐 公开' }}
          · 下载 {{ s.downloadCount }}{{ s.maxDownloads ? '/' + s.maxDownloads : '' }}
          · 访问 {{ s.visitCount }}
        </div>
        <div class="share-meta text-muted" v-if="s.expireAt">过期：{{ new Date(s.expireAt).toLocaleDateString('zh-CN') }}</div>
      </div>
      <div class="share-actions">
        <button class="btn btn-ghost btn-sm" @click="copyLink(s.shareCode)">复制链接</button>
        <button class="btn btn-ghost btn-sm" style="color:var(--red)" @click="cancelShare(s.id)">取消</button>
      </div>
    </div>
    <!-- Create dialog -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog animate-scale-in">
        <h3>创建分享</h3>
        <div class="form-group"><label>文件 ID</label><input v-model="newShare.fileId" type="number" class="input" /></div>
        <div class="form-group"><label>密码（可选）</label><input v-model="newShare.password" class="input" /></div>
        <div class="form-group"><label>过期时间（可选，epoch ms）</label><input v-model="newShare.expireAt" type="number" class="input" /></div>
        <div class="form-group"><label>最大下载次数（可选）</label><input v-model="newShare.maxDownloads" type="number" class="input" /></div>
        <div class="dialog-actions">
          <button class="btn btn-ghost btn-sm" @click="showCreate = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="createShare">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '@/api/client'
const shares = ref<any[]>([])
const showCreate = ref(false)
const newShare = ref({ fileId: '', password: '', expireAt: '', maxDownloads: '' })
async function fetch() { const { data } = await api.get('/shares'); shares.value = data.data.records }
async function createShare() {
  const body: any = { fileId: Number(newShare.value.fileId) }
  if (newShare.value.password) body.password = newShare.value.password
  if (newShare.value.expireAt) body.expireAt = Number(newShare.value.expireAt)
  if (newShare.value.maxDownloads) body.maxDownloads = Number(newShare.value.maxDownloads)
  await api.post('/shares', body); showCreate.value = false; fetch()
}
async function cancelShare(id: number) { if (confirm('取消分享？')) { await api.delete(`/shares/${id}`); fetch() } }
function copyLink(code: string) { navigator.clipboard.writeText(`${window.location.origin}/s/${code}`) }
onMounted(fetch)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.share-card { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.share-info { flex: 1; }
.share-code { font-size: 15px; font-weight: 600; color: var(--accent); margin-bottom: 4px; }
.share-meta { font-size: 12px; }
.share-actions { display: flex; gap: 8px; }
.empty-state { padding: 60px 20px; text-align: center; color: var(--text-muted); }
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.dialog { background: var(--bg-elevated); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 24px; width: 400px; max-width: 90vw; display: flex; flex-direction: column; gap: 14px; }
.dialog h3 { font-size: 16px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-group label { font-size: 11px; text-transform: uppercase; color: var(--text-secondary); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
