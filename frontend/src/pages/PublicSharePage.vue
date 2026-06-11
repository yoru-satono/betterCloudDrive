<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as sharesApi from '@/api/shares'
import type { FileEntity } from '@/types/file'
import type { ShareLinkEntity } from '@/types/share'

const route = useRoute()
const { formatSize, formatDate } = useFormatters()

const loading = ref(true)
const file = ref<FileEntity | null>(null)
const share = ref<ShareLinkEntity | null>(null)
const error = ref('')
const needPassword = ref(false)
const password = ref('')
const downloading = ref(false)

async function load(pw?: string) {
  try {
    const { data } = await sharesApi.accessShare(route.params.shareCode as string, pw)
    file.value = data.data.file
    share.value = data.data.share
    needPassword.value = false
    error.value = ''
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } }).response?.status
    if (status === 403) { needPassword.value = true }
    else if (status === 404) { error.value = '分享链接不存在或已过期' }
    else { error.value = '加载失败' }
  } finally { loading.value = false }
}

async function submitPassword() {
  loading.value = true
  await load(password.value)
}

async function download() {
  if (!file.value) return
  downloading.value = true
  try {
    const { data } = await sharesApi.getShareDownloadUrl(route.params.shareCode as string, password.value || undefined)
    const a = document.createElement('a')
    a.href = data.data.url; a.download = data.data.fileName
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
  } finally { downloading.value = false }
}

onMounted(() => load())
</script>

<template>
  <div class="share-page">
    <div class="share-page__card">
      <!-- Brand -->
      <div class="share-page__brand">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2">
          <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
          <polyline points="9 22 9 12 15 12 15 22"/>
        </svg>
        BetterDrive
      </div>

      <div v-if="loading" class="share-page__loading"><OSpinner /></div>

      <div v-else-if="error" class="share-page__error">
        <p>{{ error }}</p>
        <RouterLink to="/login">登录云盘</RouterLink>
      </div>

      <template v-else-if="needPassword">
        <div class="share-page__title">此分享需要密码</div>
        <div style="display:flex;flex-direction:column;gap:12px;margin-top:16px">
          <OInput v-model="password" label="访问密码" type="password" placeholder="输入密码" @keyup.enter="submitPassword" />
          <OButton variant="primary" :loading="loading" style="justify-content:center" @click="submitPassword">确认</OButton>
        </div>
      </template>

      <template v-else-if="file">
        <div class="share-page__file">
          <div class="share-page__file-icon">
            <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="32" />
          </div>
          <div class="share-page__file-name">{{ file.fileName }}</div>
          <div class="share-page__file-meta">
            <span class="font-mono">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }}</span>
            <span class="text-muted">· {{ formatDate(file.updatedAt) }}</span>
          </div>
          <OButton
            v-if="file.fileType !== 'folder'"
            variant="primary"
            :loading="downloading"
            style="margin-top:20px;justify-content:center"
            @click="download"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
            下载文件
          </OButton>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.share-page {
  min-height: 100vh; background: var(--bg-base);
  display: flex; align-items: center; justify-content: center; padding: 20px;
}
.share-page__card {
  width: 100%; max-width: 400px;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 14px; padding: 28px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.4);
}
.share-page__brand {
  display: flex; align-items: center; gap: 8px;
  font-size: 14px; font-weight: 600; color: var(--text-secondary);
  margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border);
}
.share-page__loading { display: flex; justify-content: center; padding: 30px; }
.share-page__error { text-align: center; color: var(--danger); padding: 20px; }
.share-page__title { font-size: 16px; font-weight: 600; }
.share-page__file { display: flex; flex-direction: column; align-items: center; padding: 10px 0; }
.share-page__file-icon { margin-bottom: 16px; }
.share-page__file-name { font-size: 16px; font-weight: 600; text-align: center; margin-bottom: 8px; word-break: break-all; }
.share-page__file-meta { font-size: 13px; color: var(--text-secondary); display: flex; gap: 8px; }
</style>
