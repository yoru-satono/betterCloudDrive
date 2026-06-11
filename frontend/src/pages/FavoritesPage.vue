<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OButton from '@/components/base/OButton.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as favApi from '@/api/favorites'
import { downloadFile } from '@/api/download'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'

const { formatSize, formatDate } = useFormatters()
const files = ref<FileEntity[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await favApi.listFavorites(1, 100)
    files.value = data.data.records
  } finally { loading.value = false }
}

async function removeFav(file: FileEntity) {
  await favApi.removeFavorite(file.id)
  toast.success('已取消收藏')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <h2>我的收藏</h2>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="files.length === 0" title="暂无收藏" description="在文件上右键选择「收藏」即可添加" />
    <div v-else class="fav-list">
      <div v-for="file in files" :key="file.id" class="fav-item">
        <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="16" />
        <div class="fav-item__name truncate">{{ file.fileName }}</div>
        <div class="fav-item__meta">
          <span class="font-mono">{{ file.fileType === 'folder' ? '文件夹' : formatSize(file.fileSize) }}</span>
          <span class="text-muted">{{ formatDate(file.updatedAt) }}</span>
        </div>
        <div class="fav-item__actions">
          <OButton v-if="file.fileType !== 'folder'" variant="subtle" size="sm" @click="downloadFile(file.id, file.fileName)">下载</OButton>
          <OButton variant="subtle" size="sm" @click="removeFav(file)">取消收藏</OButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header { margin-bottom: 20px; }
.page-loading { display: flex; justify-content: center; padding: 60px; }
.fav-list { display: flex; flex-direction: column; gap: 4px; }
.fav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border-radius: 8px;
  border: 1px solid var(--border); background: var(--bg-elevated);
  transition: border-color var(--fast);
}
.fav-item:hover { border-color: var(--border-hover); }
.fav-item__name { flex: 1; font-size: 13px; min-width: 0; }
.fav-item__meta { display: flex; gap: 12px; font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
.fav-item__actions { display: flex; gap: 4px; flex-shrink: 0; }
</style>
