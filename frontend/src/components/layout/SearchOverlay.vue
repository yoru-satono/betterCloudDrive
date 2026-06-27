<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { onKeyStroke } from '@vueuse/core'
import { useUIStore } from '@/stores/ui'
import { useFilesStore } from '@/stores/files'
import { useSearchContextStore } from '@/stores/searchContext'
import { getSearchInputPlaceholder, resolveSearchMode, type SearchMode } from '@/config/search'
import * as favApi from '@/api/favorites'
import * as recycleApi from '@/api/recycle'
import * as sharesApi from '@/api/shares'
import * as tagsApi from '@/api/tags'
import { buildShareUrl } from '@/config/runtime'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import { dispatchFileAction } from '@/components/file/fileActions'
import FileContextMenu from '@/components/file/FileContextMenu.vue'
import { useContextMenu } from '@/composables/useContextMenu'
import { downloadFile, downloadFolderZip } from '@/api/download'
import { usePreviewStore } from '@/stores/preview'
import type { FileEntity } from '@/types/file'
import type { ShareLinkEntity } from '@/types/share'
import type { TagEntity } from '@/types/tag'

type SearchResult =
  | { type: 'file'; source: SearchMode | 'tag-files'; file: FileEntity }
  | { type: 'share'; share: ShareLinkEntity }
  | { type: 'tag'; tag: TagEntity }

const ui = useUIStore()
const router = useRouter()
const route = useRoute()
const files = useFilesStore()
const searchContext = useSearchContextStore()
const ctx = useContextMenu()
const preview = usePreviewStore()
const { formatSize, formatDateFull } = useFormatters()

const q = ref('')
const loading = ref(false)
const results = ref<SearchResult[]>([])
const searchMode = computed(() => resolveSearchMode(route))
const inputPlaceholder = computed(() => getSearchInputPlaceholder(searchMode.value, searchContext.activeTag))
let searchSeq = 0

onKeyStroke('k', (e) => {
  if (e.metaKey || e.ctrlKey) { e.preventDefault(); ui.openSearch() }
})
onKeyStroke('Escape', () => { if (ui.searchOpen) ui.closeSearch() })

watch(
  [q, () => searchMode.value.mode, () => searchContext.activeTag?.id],
  () => performSearch(),
)

async function performSearch() {
  const keyword = q.value.trim()
  const seq = ++searchSeq
  if (!keyword) {
    results.value = []
    loading.value = false
    files.clearSearchResults()
    return
  }

  loading.value = true
  try {
    const nextResults = await searchByMode(searchMode.value.mode, keyword)
    if (seq === searchSeq) results.value = nextResults
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

async function searchByMode(mode: SearchMode, keyword: string): Promise<SearchResult[]> {
  if (mode === 'favorites') {
    const { data } = await favApi.listFavorites(1, 20, keyword)
    return data.data.records.map(file => ({ type: 'file', source: 'favorites', file }))
  }
  if (mode === 'shares') {
    const { data } = await sharesApi.listShares(1, 20, keyword)
    return data.data.records.map(share => ({ type: 'share', share }))
  }
  if (mode === 'tags') {
    const [tagsRes, filesRes] = await Promise.all([
      tagsApi.listTags(keyword),
      searchContext.activeTag
        ? tagsApi.listFilesByTag(searchContext.activeTag.id, 1, 20, keyword)
        : Promise.resolve(null),
    ])
    const tagResults: SearchResult[] = tagsRes.data.data.map(tag => ({ type: 'tag', tag }))
    const fileResults: SearchResult[] = filesRes?.data.data.records.map(file => ({
      type: 'file',
      source: 'tag-files',
      file,
    })) ?? []
    return [...tagResults, ...fileResults]
  }
  if (mode === 'recycle') {
    const { data } = await recycleApi.listRecycleBin(1, 20, keyword)
    return data.data.records.map(file => ({ type: 'file', source: 'recycle', file }))
  }

  await files.searchFiles(keyword)
  return files.searchResults.map(file => ({ type: 'file', source: 'files', file }))
}

function handleClose() {
  ui.closeSearch()
  q.value = ''
  results.value = []
  files.clearSearchResults()
}

function resultKey(result: SearchResult) {
  if (result.type === 'share') return `share-${result.share.id}`
  if (result.type === 'tag') return `tag-${result.tag.id}`
  return `${result.source}-${result.file.id}`
}

async function openResult(result: SearchResult) {
  if (result.type === 'share') {
    await router.push({ name: 'Shares', query: { shareId: result.share.id } })
    handleClose()
    return
  }
  if (result.type === 'tag') {
    await router.push({ name: 'Tags', query: { tagId: result.tag.id } })
    handleClose()
    return
  }
  if (result.source === 'recycle') {
    await router.push({ name: 'RecycleBin' })
    handleClose()
    return
  }
  await openFileResult(result.file)
}

async function openFileResult(file: FileEntity) {
  if (file.fileType === 'folder') {
    await router.push({ name: 'Folder', params: { folderId: file.id } })
    handleClose()
    return
  }

  preview.openPreview(file)
  handleClose()
}

function openSearchContextMenu(result: SearchResult, event: MouseEvent) {
  if (result.type !== 'file') return
  const file = result.file
  if (result.source === 'recycle') {
    ctx.open(event, [
      {
        label: '打开回收站',
        action: () => router.push({ name: 'RecycleBin' }).then(handleClose),
      },
    ])
    return
  }
  const handled = dispatchFileAction({ action: 'context-menu', file, event, afterAction: handleClose })
  if (handled) return
  ctx.open(event, [
    {
      label: '打开文件所在位置',
      action: () => router.push(file.parentId
        ? { name: 'Folder', params: { folderId: file.parentId } }
        : { name: 'Files' }).then(handleClose),
    },
    {
      label: '下载',
      action: () => {
        if (file.fileType === 'folder') downloadFolderZip(file.id, file.fileName)
        else downloadFile(file.id, file.fileName)
        handleClose()
      },
    },
  ])
}

async function copyShareLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(buildShareUrl(share.shareCode)).catch(() => {})
  toast.success('链接已复制')
}

function shareStatus(share: ShareLinkEntity) {
  if (share.expireAt && new Date(share.expireAt) < new Date()) return '已过期'
  return share.expireAt ? `过期：${formatDateFull(share.expireAt)}` : '永不过期'
}

function fileSourceLabel(source: SearchMode | 'tag-files') {
  if (source === 'favorites') return '收藏'
  if (source === 'recycle') return '回收站'
  if (source === 'tag-files') return searchContext.activeTag ? `标签：${searchContext.activeTag.tagName}` : '标签文件'
  return '文件'
}
</script>

<template>
  <Teleport to="body">
    <Transition name="search-overlay">
      <div v-if="ui.searchOpen" class="search-bg" @click.self="handleClose">
        <div class="search-panel">
          <div class="search-input-row">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="2">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              v-model="q"
              class="search-input"
              :placeholder="inputPlaceholder"
              autofocus
            />
            <kbd @click="handleClose">Esc</kbd>
          </div>
          <div v-if="q.length > 0" class="search-results">
            <div v-if="loading" class="search-loading">搜索中...</div>
            <div
              v-else-if="results.length === 0"
              class="search-empty"
            >{{ searchMode.emptyText }}</div>
            <div
              v-else
              v-for="result in results.slice(0, 10)"
              :key="resultKey(result)"
              class="search-item"
              @click="openResult(result)"
              @contextmenu.prevent="openSearchContextMenu(result, $event)"
            >
              <template v-if="result.type === 'file'">
                <FileIcon :mime-type="result.file.mimeType" :file-type="result.file.fileType" :size="16" />
                <span class="search-item__name truncate">{{ result.file.fileName }}</span>
                <span class="search-item__meta">{{ fileSourceLabel(result.source) }}</span>
                <span class="search-item__size font-mono">
                  {{ result.file.fileType === 'folder' ? '文件夹' : formatSize(result.file.fileSize) }}
                </span>
              </template>

              <template v-else-if="result.type === 'share'">
                <span class="search-item__share-icon" aria-hidden="true">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M4 12v8a2 2 0 002 2h12a2 2 0 002-2v-8M16 6l-4-4-4 4M12 2v13" />
                  </svg>
                </span>
                <span class="search-item__name truncate">{{ buildShareUrl(result.share.shareCode) }}</span>
                <span class="search-item__meta">
                  文件 {{ result.share.fileId }} · {{ shareStatus(result.share) }} · {{ result.share.hasPassword ? '有密码' : '无密码' }}
                </span>
                <button class="search-item__action" type="button" @click.stop="copyShareLink(result.share)">复制</button>
              </template>

              <template v-else>
                <span class="search-item__tag-dot" :style="{ background: result.tag.color || '#00d4aa' }" />
                <span class="search-item__name truncate">{{ result.tag.tagName }}</span>
                <span class="search-item__meta">标签</span>
                <span class="search-item__size font-mono">{{ result.tag.fileCount ?? 0 }} 个文件</span>
              </template>
            </div>
          </div>
        </div>
        <FileContextMenu
          :visible="ctx.visible.value"
          :x="ctx.x.value"
          :y="ctx.y.value"
          :items="ctx.items.value"
          @close="ctx.close"
        />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.search-bg {
  position: fixed; inset: 0; z-index: 500;
  background: rgba(0,0,0,0.65);
  backdrop-filter: blur(6px);
  display: flex; align-items: flex-start; justify-content: center;
  padding-top: 15vh;
}
.search-panel {
  width: 640px; max-width: 95vw;
  background: var(--bg-overlay);
  border: 1px solid var(--border-hover);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 24px 80px rgba(0,0,0,0.6);
}
.search-input-row {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}
.search-input {
  flex: 1; background: transparent; border: none; outline: none;
  color: var(--text-primary); font-family: var(--font-sans); font-size: 15px;
}
.search-input::placeholder { color: var(--text-muted); }
kbd {
  font-size: 11px; font-family: var(--font-mono); cursor: pointer;
  background: var(--bg-elevated); border: 1px solid var(--border-hover);
  border-radius: 4px; padding: 2px 6px; color: var(--text-muted);
}
.search-results { padding: 6px; max-height: 380px; overflow-y: auto; }
.search-loading, .search-empty { padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px; }
.search-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 7px; cursor: pointer;
  transition: background var(--fast);
}
.search-item:hover { background: var(--bg-elevated); }
.search-item__name { flex: 1; min-width: 0; font-size: 13px; }
.search-item__meta { font-size: 11px; color: var(--text-muted); flex-shrink: 0; }
.search-item__size { font-size: 11px; color: var(--text-secondary); flex-shrink: 0; }
.search-item__share-icon {
  width: 16px; height: 16px; display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-secondary); flex-shrink: 0;
}
.search-item__tag-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.search-item__action {
  border: 1px solid var(--border);
  background: var(--bg-surface);
  color: var(--text-secondary);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
  cursor: pointer;
}
.search-item__action:hover { color: var(--accent); border-color: rgba(0, 212, 170, 0.25); }

.search-overlay-enter-active { transition: all 160ms var(--ease-out); }
.search-overlay-leave-active { transition: all 100ms; }
.search-overlay-enter-from { opacity: 0; }
.search-overlay-leave-to   { opacity: 0; }
.search-overlay-enter-from .search-panel { transform: scale(0.97) translateY(-8px); }
.search-panel { transition: transform 160ms var(--ease-out); }
</style>
