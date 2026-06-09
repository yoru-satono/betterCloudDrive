<template>
  <div class="animate-fade-in">
    <h2 class="page-title">分享文件</h2>
    <p class="page-sub" v-if="!unlocked">输入密码访问此分享</p>
    <div v-if="!unlocked && needPassword" class="form">
      <input v-model="pwd" type="password" class="input" placeholder="输入分享密码" @keyup.enter="accessShare" />
      <button class="btn btn-primary btn-full" @click="accessShare" style="margin-top:12px">访问</button>
      <p v-if="err" class="error-msg">{{ err }}</p>
    </div>
    <div v-if="!unlocked && !needPassword">
      <p>加载中...</p>
    </div>
    <div v-if="unlocked && file" class="card">
      <div class="file-icon-lg" style="text-align:center;margin-bottom:16px">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="var(--accent)" opacity="0.15" stroke="var(--accent)" stroke-width="1.5"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14,2 14,8 20,8"/></svg>
      </div>
      <h3 style="text-align:center">{{ file.fileName }}</h3>
      <p class="text-muted text-center" style="margin-top:4px">{{ formatSize(file.fileSize) }} · {{ file.fileType }}</p>
      <div style="text-align:center;margin-top:20px">
        <a :href="`/api/v1/download/${file.fileId}`" class="btn btn-primary" target="_blank">下载文件</a>
      </div>
      <div v-if="files.length > 0" style="margin-top:24px">
        <h4 style="margin-bottom:12px">文件夹内容</h4>
        <div v-for="f in files" :key="f.id" class="file-row">
          <span class="col-name truncate">{{ f.fileName }}</span>
          <span class="col-size text-mono text-muted">{{ formatSize(f.fileSize) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api/client'
const route = useRoute()
const shareCode = route.params.shareCode as string
const unlocked = ref(false); const file = ref<any>(null); const files = ref<any[]>([])
const needPassword = ref(false); const pwd = ref(''); const err = ref('')
async function accessShare(p?: string) {
  try {
    const body = p ? { password: p } : (needPassword.value ? { password: pwd.value } : {})
    const { data } = await api.post(`/shares/access/${shareCode}`, body)
    file.value = data.data; unlocked.value = true
    const fres = await api.get(`/shares/access/${shareCode}/files`)
    files.value = fres.data.data.records
  } catch (e: any) {
    if (e.response?.data?.code === 419003) { needPassword.value = true; err.value = '密码错误' }
    else err.value = e.response?.data?.message || '访问失败'
  }
}
function formatSize(b: number) { if (!b) return '0 B'; if (b < 1048576) return (b/1024).toFixed(1)+' KB'; return (b/1048576).toFixed(1)+' MB' }
onMounted(() => accessShare())
</script>
<style scoped>
.page-title { text-align: center; font-size: 24px; }
.page-sub { text-align: center; color: var(--text-secondary); margin-top: 6px; margin-bottom: 24px; }
.form { display: flex; flex-direction: column; }
.btn-full { width: 100%; justify-content: center; padding: 12px; }
.error-msg { color: var(--red); font-size: 13px; text-align: center; margin-top: 12px; }
.text-center { text-align: center; }
.file-row { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-subtle); }
.col-name { flex: 1; }
.col-size { width: 90px; text-align: right; }
</style>
