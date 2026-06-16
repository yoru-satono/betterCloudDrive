<script setup lang="ts">
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
const stack = ref<Array<{ id: number | null; name: string }>>([{ id: null, name: '根目录' }])
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
  const ok = await confirm('管理员删除文件', `确认删除「${file.fileName}」？`)
  if (!ok) return
  await adminApi.deleteFile(file.id)
  toast.success('文件已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>用户 {{ userId }} 的文件</h2>
        <p class="text-muted" style="font-size:12px;margin-top:2px">{{ stack.map(item => item.name).join(' / ') }}</p>
      </div>
      <OButton variant="ghost" :disabled="stack.length <= 1 || loading" @click="goBack">返回上级</OButton>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="files.length === 0" title="当前目录没有文件" />
    <div v-else class="admin-files">
      <div v-for="file in files" :key="file.id" class="admin-file-row">
        <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="16" />
        <button v-if="file.fileType === 'folder'" class="admin-file-row__name" @click="enterFolder(file)">{{ file.fileName }}</button>
        <span v-else class="admin-file-row__name">{{ file.fileName }}</span>
        <span class="font-mono">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }}</span>
        <OButton variant="subtle" size="sm" @click="openDetail(file)">详情</OButton>
        <OButton variant="danger" size="sm" @click="deleteFile(file)">删除</OButton>
      </div>
    </div>

    <OModal title="文件详情" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detail" class="admin-file-info">
        <div><span>ID</span><strong class="font-mono">{{ detail.id }}</strong></div>
        <div><span>名称</span><strong>{{ detail.fileName }}</strong></div>
        <div><span>类型</span><strong>{{ detail.fileType === 'folder' ? '文件夹' : detail.mimeType || '文件' }}</strong></div>
        <div><span>大小</span><strong class="font-mono">{{ detail.fileType === 'folder' ? '文件夹' : formatSize(detail.fileSize) }}</strong></div>
        <div><span>MD5</span><strong class="font-mono">{{ detail.md5Hash || '-' }}</strong></div>
        <div><span>创建</span><strong>{{ formatDateFull(detail.createdAt) }}</strong></div>
        <div><span>更新</span><strong>{{ formatDateFull(detail.updatedAt) }}</strong></div>
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
