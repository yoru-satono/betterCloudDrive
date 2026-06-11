<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OButton from '@/components/base/OButton.vue'
import OModal from '@/components/base/OModal.vue'
import OInput from '@/components/base/OInput.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useFormatters } from '@/composables/useFormatters'
import * as tagsApi from '@/api/tags'
import { toast } from 'vue-sonner'
import type { TagEntity } from '@/types/tag'
import type { FileEntity } from '@/types/file'

const { confirm } = useConfirm()
const { formatSize } = useFormatters()
const tags = ref<TagEntity[]>([])
const loading = ref(false)
const activeTag = ref<TagEntity | null>(null)
const tagFiles = ref<FileEntity[]>([])
const showCreate = ref(false)
const newTagName = ref('')
const newTagColor = ref('#00d4aa')

const PRESET_COLORS = ['#00d4aa', '#60a5fa', '#a78bfa', '#f87171', '#fb923c', '#4ade80', '#f472b6']

async function load() {
  loading.value = true
  try {
    const { data } = await tagsApi.listTags()
    tags.value = data.data
  } finally { loading.value = false }
}

async function createTag() {
  if (!newTagName.value.trim()) return
  await tagsApi.createTag(newTagName.value.trim(), newTagColor.value)
  toast.success('标签已创建')
  newTagName.value = ''
  showCreate.value = false
  load()
}

async function deleteTag(tag: TagEntity) {
  const ok = await confirm('删除标签', `删除标签「${tag.tagName}」不会删除文件。`)
  if (!ok) return
  await tagsApi.deleteTag(tag.id)
  toast.success('标签已删除')
  if (activeTag.value?.id === tag.id) activeTag.value = null
  load()
}

async function selectTag(tag: TagEntity) {
  activeTag.value = tag
  const { data } = await tagsApi.listFilesByTag(tag.id, 1, 50)
  tagFiles.value = data.data.records
}

onMounted(load)
</script>

<template>
  <div class="tags-page page-enter">
    <div class="tags-page__sidebar">
      <div class="tags-page__sidebar-head">
        <h3>标签</h3>
        <OButton variant="ghost" size="sm" @click="showCreate = true">+ 新建</OButton>
      </div>

      <div v-if="loading" class="page-loading"><OSpinner size="sm" /></div>
      <OEmptyState v-else-if="tags.length === 0" title="暂无标签" />
      <div v-else class="tags-list">
        <div
          v-for="tag in tags"
          :key="tag.id"
          class="tag-item"
          :class="{ 'tag-item--active': activeTag?.id === tag.id }"
          @click="selectTag(tag)"
        >
          <span class="tag-dot" :style="{ background: tag.color || '#00d4aa' }" />
          <span class="tag-item__name truncate">{{ tag.tagName }}</span>
          <span class="tag-item__count">{{ tag.fileCount }}</span>
          <button class="tag-item__del" @click.stop="deleteTag(tag)">
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <div class="tags-page__main">
      <template v-if="activeTag">
        <h3 style="margin-bottom:16px">
          <span class="tag-dot" :style="{ background: activeTag.color || '#00d4aa' }" />
          {{ activeTag.tagName }} ({{ tagFiles.length }})
        </h3>
        <OEmptyState v-if="tagFiles.length === 0" title="此标签下暂无文件" />
        <div v-else class="tag-files">
          <div v-for="f in tagFiles" :key="f.id" class="tag-file-row">
            <FileIcon :mime-type="f.mimeType" :file-type="f.fileType" :size="15" />
            <span class="truncate" style="flex:1;font-size:13px">{{ f.fileName }}</span>
            <span class="font-mono" style="font-size:11px;color:var(--text-secondary)">{{ f.fileType === 'folder' ? '文件夹' : formatSize(f.fileSize) }}</span>
          </div>
        </div>
      </template>
      <OEmptyState v-else title="选择左侧标签查看文件" />
    </div>

    <OModal title="新建标签" :open="showCreate" @close="showCreate = false">
      <div style="display:flex;flex-direction:column;gap:14px">
        <OInput v-model="newTagName" label="标签名称" placeholder="输入标签名" @keyup.enter="createTag" />
        <div>
          <div style="font-size:12px;color:var(--text-secondary);margin-bottom:8px">颜色</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <button
              v-for="c in PRESET_COLORS" :key="c"
              class="color-btn"
              :style="{ background: c, outline: newTagColor === c ? '2px solid white' : 'none' }"
              @click="newTagColor = c"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="showCreate = false">取消</OButton>
        <OButton variant="primary" @click="createTag">创建</OButton>
      </template>
    </OModal>
  </div>
</template>

<style scoped>
.tags-page { display: flex; gap: 20px; height: 100%; }
.tags-page__sidebar { width: 220px; flex-shrink: 0; }
.tags-page__sidebar-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.tags-page__main { flex: 1; min-width: 0; }

.tags-list { display: flex; flex-direction: column; gap: 2px; }
.tag-item {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px; border-radius: 7px; cursor: pointer;
  transition: background var(--fast);
}
.tag-item:hover { background: var(--bg-elevated); }
.tag-item--active { background: var(--accent-dim); }
.tag-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tag-item__name { flex: 1; font-size: 13px; }
.tag-item__count { font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); }
.tag-item__del {
  background: none; border: none; cursor: pointer; color: var(--text-muted);
  opacity: 0; display: flex; align-items: center; transition: opacity var(--fast);
}
.tag-item:hover .tag-item__del { opacity: 1; }
.tag-item__del:hover { color: var(--danger); }

.tag-files { display: flex; flex-direction: column; gap: 4px; }
.tag-file-row {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: 7px; border: 1px solid var(--border);
  background: var(--bg-elevated);
}

.page-loading { display: flex; justify-content: center; padding: 20px; }
.color-btn {
  width: 24px; height: 24px; border-radius: 50%; border: none; cursor: pointer;
  transition: transform var(--fast);
}
.color-btn:hover { transform: scale(1.15); }
</style>
