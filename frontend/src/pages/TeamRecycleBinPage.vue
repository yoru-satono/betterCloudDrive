<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import FileIcon from '@/components/file/FileIcon.vue'
import { useFormatters } from '@/composables/useFormatters'
import { useConfirm } from '@/composables/useConfirm'
import * as teamsApi from '@/api/teams'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'
import type { Team } from '@/types/team'

const route = useRoute()
const router = useRouter()
const { formatSize, formatDate } = useFormatters()
const { confirm } = useConfirm()

const teamId = computed(() => Number(route.params.teamId))
const team = ref<Team | null>(null)
const files = ref<FileEntity[]>([])
const loading = ref(false)
const selected = ref<Set<number>>(new Set())

const canWrite = computed(() => ['OWNER', 'ADMIN', 'EDITOR'].includes(team.value?.role || ''))

async function load() {
  if (!Number.isFinite(teamId.value)) return
  loading.value = true
  try {
    const [teamRes, fileRes] = await Promise.all([
      teamsApi.getTeam(teamId.value),
      teamsApi.listTeamRecycleBin(teamId.value, 1, 100),
    ])
    team.value = teamRes.data.data
    files.value = fileRes.data.data.records
    selected.value.clear()
  } finally {
    loading.value = false
  }
}

async function restore() {
  if (!canWrite.value) return
  const ids = [...selected.value]
  if (!ids.length) return
  await teamsApi.restoreTeamFiles(teamId.value, ids)
  toast.success(__t('team.recyclebin.restored'))
  await load()
}

async function permanentDelete() {
  if (!canWrite.value) return
  const ids = [...selected.value]
  if (!ids.length) return
  const ok = await confirm(__t('team.recyclebin.deletePermanently'), __t('team.teamrecyclebin.teamFilesWillBePermanentlyDeletedAndCannotBeRecovered', { p0: ids.length }))
  if (!ok) return
  await teamsApi.permanentDeleteTeamFiles(teamId.value, ids)
  toast.success(__t('team.recyclebin.permanentlyDeleted'))
  await load()
}

async function emptyBin() {
  if (!canWrite.value) return
  const ok = await confirm(__t('team.teamrecyclebin.emptyTheTeamRecycleBin'), __t('team.teamrecyclebin.allFilesInTheTeamRecycleBinWillBePermanently'))
  if (!ok) return
  await teamsApi.emptyTeamRecycleBin(teamId.value)
  toast.success(__t('team.teamrecyclebin.theTeamRecycleBinHasBeenEmptied'))
  await load()
}

function toggle(id: number) {
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
}

watch(teamId, () => load())
onMounted(load)
</script>

<template>
  <div class="team-bin page-enter">
    <div class="team-bin__header">
      <div>
        <h2>{{ team?.name || __t('commonUi.adminteams.team') }}{{ __t('commonUi.searchoverlay.recycleBin') }}</h2>
        <p class="text-muted">{{ __t('team.teamrecyclebin.filesDeletedByTheTeamWillBeRetainedHereAnd') }}</p>
      </div>
      <div class="team-bin__actions">
        <OButton variant="ghost" size="sm" @click="router.push({ name: 'TeamFiles', params: { teamId } })">{{ __t('team.teamrecyclebin.returnFile') }}</OButton>
        <OButton v-if="canWrite && selected.size > 0" variant="ghost" size="sm" @click="restore">{{ __t('team.recyclebin.recover') }}{{ selected.size }})</OButton>
        <OButton v-if="canWrite && selected.size > 0" variant="danger" size="sm" @click="permanentDelete">{{ __t('team.recyclebin.deletePermanentlyCountPrefix') }}{{ selected.size }})</OButton>
        <OButton v-if="canWrite && files.length > 0" variant="danger" size="sm" @click="emptyBin">{{ __t('team.recyclebin.emptyRecycleBin') }}</OButton>
      </div>
    </div>

    <div v-if="loading" class="team-bin__loading"><OSpinner /></div>
    <OEmptyState v-else-if="files.length === 0" :title="__t('team.teamrecyclebin.teamRecycleBinIsEmpty')" :description="__t('team.teamrecyclebin.deletedTeamFilesWillBeDisplayedHere')" />
    <div v-else class="team-bin__table">
      <div class="team-bin__table-header">
        <div></div>
        <div>{{ __t('commonUi.filelist.name') }}</div>
        <div>{{ __t('commonUi.filelist.size') }}</div>
        <div>{{ __t('team.recyclebin.deleteTime') }}</div>
      </div>
      <div
        v-for="file in files"
        :key="file.id"
        class="team-bin__row"
        :class="{ 'team-bin__row--selected': selected.has(file.id) }"
        @click="toggle(file.id)"
      >
        <div class="team-bin__check">
          <div class="team-bin__checkbox" :class="{ 'team-bin__checkbox--on': selected.has(file.id) }">
            <svg v-if="selected.has(file.id)" width="10" height="10" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3" fill="none" stroke-linecap="round">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
          </div>
        </div>
        <div class="team-bin__name">
          <FileIcon :mime-type="file.mimeType" :file-type="file.fileType" :size="15" />
          <span class="truncate">{{ file.fileName }}</span>
        </div>
        <div class="team-bin__size font-mono">{{ file.fileType === 'folder' ? '-' : formatSize(file.fileSize) }}</div>
        <div class="team-bin__date">{{ formatDate(file.updatedAt) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.team-bin__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.team-bin__header h2 {
  margin: 0;
}
.team-bin__header p {
  margin: 4px 0 0;
  font-size: 12px;
}
.team-bin__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.team-bin__loading {
  display: flex;
  justify-content: center;
  padding: 60px;
}
.team-bin__table {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.team-bin__table-header,
.team-bin__row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 90px 130px;
  gap: 8px;
  align-items: center;
}
.team-bin__table-header {
  padding: 8px 12px;
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
.team-bin__row {
  padding: 10px 12px;
  cursor: pointer;
  transition: background var(--fast);
  border-bottom: 1px solid var(--border);
}
.team-bin__row:last-child {
  border-bottom: 0;
}
.team-bin__row:hover {
  background: var(--bg-elevated);
}
.team-bin__row--selected {
  background: var(--accent-dim);
}
.team-bin__check {
  display: flex;
  align-items: center;
  justify-content: center;
}
.team-bin__checkbox {
  width: 16px;
  height: 16px;
  border: 1px solid var(--border-hover);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.team-bin__checkbox--on {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--text-on-accent);
}
.team-bin__name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
}
.team-bin__size,
.team-bin__date {
  font-size: 11px;
  color: var(--text-secondary);
  text-align: right;
}
</style>
