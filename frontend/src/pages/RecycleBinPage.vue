<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import { useConfirm } from '@/composables/useConfirm'
import * as recycleApi from '@/api/recycle'
import { toast } from 'vue-sonner'
import type { RecycleBinItem } from '@/types/file'

const { formatSize, formatDate } = useFormatters()
const { confirm } = useConfirm()

const files = ref<RecycleBinItem[]>([])
const loading = ref(false)
const selected = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  try {
    const { data } = await recycleApi.listRecycleBin(1, 100)
    files.value = data.data.records
  } finally { loading.value = false }
}

async function restore() {
  const ids = [...selected.value]
  await recycleApi.restoreFiles(ids)
  toast.success(__t('team.recyclebin.restored'))
  selected.value.clear()
  load()
}

async function permanentDelete() {
  const ids = [...selected.value]
  const ok = await confirm(__t('team.recyclebin.deletePermanently'), __t('recycle.recyclebin.filesWillBePermanentlyDeletedAndCannotBeRecovered', { p0: ids.length }))
  if (!ok) return
  await recycleApi.permanentDelete(ids)
  toast.success(__t('team.recyclebin.permanentlyDeleted'))
  selected.value.clear()
  load()
}

async function emptyBin() {
  const ok = await confirm(__t('team.recyclebin.emptyRecycleBin'), __t('recycle.recyclebin.allFilesInTheRecycleBinWillBePermanentlyDeleted'))
  if (!ok) return
  await recycleApi.emptyRecycleBin()
  toast.success(__t('recycle.recyclebin.recycleBinEmptied'))
  load()
}

function toggle(id: number) {
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>{{ __t('commonUi.searchoverlay.recycleBin') }}</h2>
        <p class="text-muted" style="font-size:12px;margin-top:2px">{{ __t('recycle.recyclebin.filesAreAutomaticallyDeletedAfter30Days') }}</p>
      </div>
      <div style="display:flex;gap:8px">
        <OButton v-if="selected.size > 0" variant="ghost" size="sm" @click="restore">{{ __t('team.recyclebin.recover') }}{{ selected.size }})</OButton>
        <OButton v-if="selected.size > 0" variant="danger" size="sm" @click="permanentDelete">{{ __t('team.recyclebin.deletePermanentlyCountPrefix') }}{{ selected.size }})</OButton>
        <OButton v-if="files.length > 0" variant="danger" size="sm" @click="emptyBin">{{ __t('team.recyclebin.emptyRecycleBin') }}</OButton>
      </div>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="files.length === 0" :title="__t('recycle.recyclebin.recycleBinIsEmpty')" :description="__t('recycle.recyclebin.deletedFilesWillBeDisplayedHere')" />
    <div v-else class="file-table">
      <div class="file-table__header">
        <div></div><div>{{ __t('commonUi.filelist.name') }}</div><div>{{ __t('commonUi.filelist.size') }}</div><div>{{ __t('team.recyclebin.deleteTime') }}</div>
      </div>
      <div
        v-for="file in files" :key="file.id"
        class="file-table__row"
        :class="{ 'file-table__row--selected': selected.has(file.id) }"
        @click="toggle(file.id)"
      >
        <div class="file-table__check">
          <div class="checkbox" :class="{ 'checkbox--on': selected.has(file.id) }">
            <svg v-if="selected.has(file.id)" width="10" height="10" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3" fill="none" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>
          </div>
        </div>
        <div class="file-table__name">
          <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="15" />
          <span class="truncate">{{ file.fileName }}</span>
        </div>
        <div class="file-table__size font-mono">{{ file.fileType === 'folder' ? '—' : formatSize(file.fileSize) }}</div>
        <div class="file-table__date">{{ formatDate(file.deletedAt) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
.page-loading { display: flex; justify-content: center; padding: 60px; }
.file-table { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.file-table__header {
  display: grid; grid-template-columns: 40px 1fr 90px 130px;
  padding: 8px 12px; gap: 8px;
  font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em;
  background: var(--bg-elevated); border-bottom: 1px solid var(--border);
}
.file-table__row {
  display: grid; grid-template-columns: 40px 1fr 90px 130px;
  padding: 10px 12px; gap: 8px; align-items: center;
  cursor: pointer; transition: background var(--fast);
  border-bottom: 1px solid var(--border);
}
.file-table__row:last-child { border-bottom: none; }
.file-table__row:hover { background: var(--bg-elevated); }
.file-table__row--selected { background: var(--accent-dim); }
.file-table__check { display: flex; align-items: center; justify-content: center; }
.checkbox {
  width: 16px; height: 16px; border: 1px solid var(--border-hover); border-radius: 4px;
  display: flex; align-items: center; justify-content: center; transition: all var(--fast);
}
.checkbox--on { background: var(--accent); border-color: var(--accent); color: var(--text-on-accent); }
.file-table__name { display: flex; align-items: center; gap: 8px; font-size: 13px; min-width: 0; }
.file-table__size { font-size: 11px; color: var(--text-secondary); text-align: right; }
.file-table__date { font-size: 11px; color: var(--text-secondary); text-align: right; }
</style>
