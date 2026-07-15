<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'

const route = useRoute()
const { confirm } = useConfirm()
const { formatSize, formatDateFull } = useFormatters()
const userId = computed(() => Number(route.params.userId))
const files = ref<FileEntity[]>([])
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref<FileEntity | null>(null)
const stack = ref<Array<{ id: number | null; name: string }>>([{ id: null, name: __t('adminUi.adminuserfiles.rootDirectory') }])
const currentParentId = computed(() => stack.value[stack.value.length - 1].id)

async function load() {
  loading.value = true
  try {
    const { data } = await adminApi.listUserFiles(userId.value, { parentId: currentParentId.value, page: 1, size: 100 })
    files.value = data.data.records
  } finally {
    loading.value = false
  }
}

async function enterFolder(file: FileEntity) {
  stack.value.push({ id: file.id, name: file.fileName })
  await load()
}

async function goBack() {
  if (stack.value.length <= 1) return
  stack.value.pop()
  await load()
}

async function openDetail(file: FileEntity) {
  const { data } = await adminApi.getFile(file.id)
  detail.value = data.data
  detailOpen.value = true
}

async function deleteFile(file: FileEntity) {
  const ok = await confirm(__t('adminUi.adminuserfiles.administratorDeletesFiles'), __t('adminUi.adminuserfiles.confirmToDelete', { p0: file.fileName }))
  if (!ok) return
  await adminApi.deleteFile(file.id)
  toast.success(__t('adminUi.adminuserfiles.fileDeleted'))
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>{{ __t('commonUi.adminlogs.user') }}{{ userId }}{{ __t('adminUi.adminuserfiles.files') }}</h2>
        <p class="text-muted" style="font-size:12px;margin-top:2px">{{ stack.map(item => item.name).join(' / ') }}</p>
      </div>
      <OButton variant="ghost" :disabled="stack.length <= 1 || loading" @click="goBack">{{ __t('adminUi.adminuserfiles.returnToPreviousLevel') }}</OButton>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="files.length === 0" :title="__t('adminUi.adminuserfiles.thereAreNoFilesInTheCurrentDirectory')" />
    <div v-else class="admin-files">
      <div v-for="file in files" :key="file.id" class="admin-file-row">
        <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="16" />
        <button v-if="file.fileType === 'folder'" class="admin-file-row__name" @click="enterFolder(file)">{{ file.fileName }}</button>
        <span v-else class="admin-file-row__name">{{ file.fileName }}</span>
        <span class="font-mono">{{ file.fileType === 'folder' ? __t('commonUi.desktopfolderupload.folder') : formatSize(file.fileSize) }}</span>
        <OButton variant="subtle" size="sm" @click="openDetail(file)">{{ __t('commonUi.adminlogs.details') }}</OButton>
        <OButton variant="danger" size="sm" @click="deleteFile(file)">{{ __t('commonUi.adminuserfiles.delete') }}</OButton>
      </div>
    </div>

    <OModal :title="__t('commonUi.adminuserfiles.documentDetails')" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detail" class="admin-file-info">
        <div><span>ID</span><strong class="font-mono">{{ detail.id }}</strong></div>
        <div><span>{{ __t('commonUi.filelist.name') }}</span><strong>{{ detail.fileName }}</strong></div>
        <div><span>{{ __t('commonUi.adminlogs.type') }}</span><strong>{{ detail.fileType === 'folder' ? __t('commonUi.desktopfolderupload.folder') : detail.mimeType || __t('commonUi.searchoverlay.document') }}</strong></div>
        <div><span>{{ __t('commonUi.filelist.size') }}</span><strong class="font-mono">{{ detail.fileType === 'folder' ? __t('commonUi.desktopfolderupload.folder') : formatSize(detail.fileSize) }}</strong></div>
        <div><span>MD5</span><strong class="font-mono">{{ detail.md5Hash || '-' }}</strong></div>
        <div><span>{{ __t('commonUi.adminuserfiles.create') }}</span><strong>{{ formatDateFull(detail.createdAt) }}</strong></div>
        <div><span>{{ __t('adminUi.adminuserfiles.renew') }}</span><strong>{{ formatDateFull(detail.updatedAt) }}</strong></div>
      </div>
    </OModal>
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
.page-loading { display: flex; justify-content: center; padding: 60px; }
.admin-files { display: flex; flex-direction: column; gap: 6px; }
.admin-file-row {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 110px auto auto;
  gap: 10px;
  align-items: center;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-elevated);
}
.admin-file-row__name {
  border: 0;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  font-family: var(--font-sans);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
button.admin-file-row__name { cursor: pointer; }
button.admin-file-row__name:hover { color: var(--accent); }
.admin-file-info { display: flex; flex-direction: column; gap: 8px; }
.admin-file-info div {
  display: grid;
  grid-template-columns: 62px 1fr;
  gap: 10px;
  font-size: 13px;
}
.admin-file-info span { color: var(--text-secondary); }
.admin-file-info strong { font-weight: 500; overflow-wrap: anywhere; }
</style>
