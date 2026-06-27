<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import FolderPickerModal from '@/components/file/FolderPickerModal.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as sharesApi from '@/api/shares'
import { downloadSharedFile, downloadSharedFolderZip } from '@/api/download'
import type { FileEntity } from '@/types/file'
import type { AccessShareResponse } from '@/types/share'

const route = useRoute()
const router = useRouter()
const { formatSize, formatDate } = useFormatters()

interface SaveTarget {
  fileId?: number
  fileName: string
}

const loading = ref(true)
const file = ref<AccessShareResponse | null>(null)
const sharedFiles = ref<FileEntity[]>([])
const error = ref('')
const needPassword = ref(false)
const password = ref('')
const accessPassword = ref<string | undefined>(undefined)
const folderPickerOpen = ref(false)
const saving = ref(false)
const saveTarget = ref<SaveTarget | null>(null)
const shareCode = computed(() => route.params.shareCode as string)

async function load(pw?: string) {
  try {
    const { data } = await sharesApi.accessShare(shareCode.value, pw)
    file.value = data.data
    const filesRes = await sharesApi.listSharedFiles(shareCode.value, null, 1, 100, pw)
    sharedFiles.value = filesRes.data.data.records
    needPassword.value = false
    accessPassword.value = pw
    error.value = ''
  } catch (e: unknown) {
    const code = (e as { response?: { data?: { code?: number } } }).response?.data?.code
    if (code === 419003) { needPassword.value = true }
    else if (code === 419002 || code === 419004) { error.value = '分享链接不存在或已过期' }
    else if (code === 419005) { error.value = '访问次数已达上限' }
    else { error.value = '加载失败' }
  } finally { loading.value = false }
}

async function submitPassword() {
  loading.value = true
  await load(password.value)
}

function download(fileId: number, fileName: string, fileType: 'file' | 'folder') {
  if (fileType === 'folder') {
    downloadSharedFolderZip(shareCode.value, fileId, fileName, accessPassword.value)
  } else {
    downloadSharedFile(shareCode.value, fileId, fileName, accessPassword.value)
  }
}

async function openFolderPicker(target: SaveTarget) {
  if (!localStorage.getItem('accessToken')) {
    await router.push('/login')
    return
  }
  saveTarget.value = target
  folderPickerOpen.value = true
}

function closeFolderPicker() {
  if (saving.value) return
  folderPickerOpen.value = false
  saveTarget.value = null
}

function saveErrorMessage(e: unknown) {
  const response = (e as { response?: { data?: { code?: number; message?: string } } }).response
  if (response?.data?.code === 409001) return '目标文件夹已存在同名项目'
  if (response?.data?.code === 419001) return response.data.message || '网盘空间不足'
  if (response?.data?.code === 419005) return '分享访问次数已达上限'
  return response?.data?.message || '保存失败'
}

async function confirmSave(targetParentId: number | null) {
  if (!saveTarget.value) return
  saving.value = true
  try {
    await sharesApi.saveSharedItem(shareCode.value, {
      fileId: saveTarget.value.fileId,
      targetParentId,
      password: accessPassword.value,
    })
    toast.success('已保存到我的网盘')
    folderPickerOpen.value = false
    saveTarget.value = null
  } catch (e) {
    toast.error(saveErrorMessage(e))
  } finally {
    saving.value = false
  }
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
        BetterCloudDrive
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
            <FileIcon :mime-type="null" :file-type="file.fileType" :size="32" />
          </div>
          <div class="share-page__file-name">{{ file.fileName }}</div>
          <div class="share-page__file-meta">
            <span class="font-mono">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }}</span>
          </div>
          <div class="share-page__main-actions">
            <OButton
              variant="primary"
              size="sm"
              @click="download(file.fileId, file.fileName, file.fileType)"
            >
              {{ file.fileType === 'folder' ? '下载文件夹' : '下载文件' }}
            </OButton>
            <OButton
              variant="ghost"
              size="sm"
              @click="openFolderPicker({ fileName: file.fileName })"
            >
              保存
            </OButton>
          </div>
          <div v-if="sharedFiles.length" class="share-page__files">
            <div v-for="item in sharedFiles" :key="item.id" class="share-page__file-row">
              <FileIcon :mime-type="item.mimeType" :file-type="item.fileType" :size="15" />
              <span class="truncate">{{ item.fileName }}</span>
              <span class="share-page__row-meta font-mono">{{ item.fileType === 'folder' ? '文件夹' : formatSize(item.fileSize) }}</span>
              <span class="share-page__row-date">{{ formatDate(item.updatedAt) }}</span>
              <div class="share-page__row-actions">
                <OButton
                  variant="subtle"
                  size="sm"
                  @click="openFolderPicker({ fileId: item.id, fileName: item.fileName })"
                >
                  保存
                </OButton>
                <OButton
                  variant="subtle"
                  size="sm"
                  @click="download(item.id, item.fileName, item.fileType)"
                >
                  {{ item.fileType === 'folder' ? '下载文件夹' : '下载' }}
                </OButton>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <FolderPickerModal
      :open="folderPickerOpen"
      title="保存到我的网盘"
      :target-name="saveTarget?.fileName"
      confirm-text="保存到此处"
      :loading="saving"
      @close="closeFolderPicker"
      @confirm="confirmSave"
    />
  </div>
</template>

<style scoped>
.share-page {
  min-height: calc(100vh - var(--desktop-titlebar-h, 0px)); background: var(--bg-base);
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
.share-page__main-actions { margin-top: 16px; display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.share-page__files { width: 100%; margin-top: 20px; display: flex; flex-direction: column; gap: 6px; }
.share-page__file-row {
  display: grid; grid-template-columns: 18px minmax(0, 1fr) auto auto auto;
  gap: 8px; align-items: center; padding: 8px 10px;
  border: 1px solid var(--border); border-radius: 8px; background: var(--bg-base);
  font-size: 12px;
}
.share-page__row-meta, .share-page__row-date { color: var(--text-secondary); font-size: 11px; }
.share-page__row-actions { justify-self: end; display: flex; gap: 4px; }
.folder-picker { display: flex; flex-direction: column; gap: 12px; }
.folder-picker__target {
  font-size: 13px; color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.folder-picker__nav {
  display: flex; align-items: center; gap: 10px;
}
.folder-picker__path {
  min-width: 0; flex: 1;
  font-size: 13px; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.folder-picker__loading, .folder-picker__empty, .folder-picker__error {
  min-height: 120px;
  display: flex; align-items: center; justify-content: center;
  color: var(--text-secondary); font-size: 13px;
}
.folder-picker__error { color: var(--danger); }
.folder-picker__list {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 260px; overflow-y: auto;
}
.folder-picker__item {
  width: 100%;
  display: grid; grid-template-columns: 20px minmax(0, 1fr);
  align-items: center; gap: 8px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-base);
  color: var(--text-primary);
  padding: 9px 10px;
  cursor: pointer;
  text-align: left;
}
.folder-picker__item:hover { border-color: var(--border-hover); background: var(--bg-elevated); }
.folder-picker__item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 560px) {
  .share-page__card { padding: 20px; }
  .share-page__file-row {
    grid-template-columns: 18px minmax(0, 1fr) auto;
  }
  .share-page__row-date { display: none; }
  .share-page__row-actions { grid-column: 1 / -1; justify-self: stretch; justify-content: flex-end; }
}
</style>
