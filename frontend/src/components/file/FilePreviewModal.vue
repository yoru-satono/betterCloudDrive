<script setup lang="ts">
import { computed, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OnlyOfficeEditor from '@/components/file/OnlyOfficeEditor.vue'
import { downloadFile, downloadTeamFile } from '@/api/download'
import * as versionsApi from '@/api/versions'
import { useConfirm } from '@/composables/useConfirm'
import { useFormatters } from '@/composables/useFormatters'
import { usePreviewStore } from '@/stores/preview'
import type { FileVersionEntity } from '@/types/file'
import { toast } from 'vue-sonner'

const preview = usePreviewStore()
const { confirm } = useConfirm()
const { formatDateFull, formatSize } = useFormatters()
const versionsOpen = ref(false)
const versionsLoading = ref(false)
const versionsError = ref('')
const versions = ref<FileVersionEntity[]>([])
const canShowTeamVersions = computed(() => preview.officeMode && !!preview.teamId && !!preview.target)
const canRestoreTeamVersion = computed(() => !!preview.officeConfig?.editable)

function downloadPreviewTarget() {
  if (!preview.target) return
  if (preview.teamId) {
    downloadTeamFile(preview.teamId, preview.target.id, preview.target.fileName)
    return
  }
  downloadFile(preview.target.id, preview.target.fileName)
}

function closePreview() {
  versionsOpen.value = false
  preview.closePreview()
}

async function openTeamVersions() {
  if (!preview.teamId || !preview.target) return
  versionsOpen.value = true
  versionsLoading.value = true
  versionsError.value = ''
  try {
    const { data } = await versionsApi.listTeamVersions(preview.teamId, preview.target.id)
    versions.value = data.data
  } catch {
    versionsError.value = '历史版本加载失败'
  } finally {
    versionsLoading.value = false
  }
}

async function downloadTeamVersion(versionNumber: number) {
  if (!preview.teamId || !preview.target) return
  await versionsApi.downloadTeamVersion(preview.teamId, preview.target.id, versionNumber, preview.target.fileName)
}

async function restoreTeamVersion(versionNumber: number) {
  if (!preview.teamId || !preview.target || !canRestoreTeamVersion.value) return
  const ok = await confirm('恢复版本', `将版本 ${versionNumber} 恢复为当前文件？当前文件会保留为历史版本。`)
  if (!ok) return
  const { data } = await versionsApi.restoreTeamVersion(preview.teamId, preview.target.id, versionNumber)
  preview.replaceTarget(data.data)
  toast.success('版本已恢复')
  await preview.refreshOfficeConfig()
  await openTeamVersions()
}
</script>

<template>
  <OModal
    title="文件预览"
    :open="preview.open"
    :width="preview.officeMode ? 'calc(100vw - 32px)' : '720px'"
    :height="preview.officeMode ? 'calc(100vh - 32px)' : undefined"
    :body-class="preview.officeMode ? 'o-modal__body--fill' : undefined"
    @close="closePreview"
  >
    <div class="preview-box" :class="{ 'preview-box--office': preview.officeMode }">
      <OSpinner v-if="preview.loading" />
      <div v-else-if="preview.error" class="preview-box__error">{{ preview.error }}</div>
      <OnlyOfficeEditor v-else-if="preview.officeMode" :office-config="preview.officeConfig" />
      <img v-else-if="preview.target?.mimeType?.startsWith('image/')" :src="preview.url" :alt="preview.target.fileName" />
      <iframe v-else-if="preview.target?.mimeType === 'application/pdf' || preview.target?.mimeType?.startsWith('text/')" :src="preview.url" />
      <div v-else class="preview-box__error">此类型暂不支持内嵌预览</div>
    </div>
    <template #footer>
      <span v-if="preview.officeMode && preview.officeConfig" class="preview-box__mode">
        {{ preview.officeConfig.editable ? '可编辑' : '只读预览' }}
      </span>
      <OButton v-if="canShowTeamVersions" variant="ghost" @click="openTeamVersions">历史版本</OButton>
      <OButton variant="ghost" @click="closePreview">关闭</OButton>
      <OButton v-if="preview.target" variant="primary" @click="downloadPreviewTarget">下载</OButton>
    </template>
  </OModal>

  <OModal title="历史版本" :open="versionsOpen" width="560px" @close="versionsOpen = false">
    <div class="office-versions">
      <div class="office-versions__target">{{ preview.target?.fileName }}</div>
      <div v-if="versionsLoading" class="office-versions__loading"><OSpinner /></div>
      <div v-else-if="versionsError" class="office-versions__empty">{{ versionsError }}</div>
      <div v-else-if="versions.length === 0" class="office-versions__empty">暂无历史版本</div>
      <div v-else class="office-versions__list">
        <div
          v-for="version in versions"
          :key="version.id"
          class="office-version-row"
          :data-testid="`office-version-row-${version.versionNumber}`"
        >
          <div>
            <div class="office-version-row__title">版本 {{ version.versionNumber }}</div>
            <div class="office-version-row__meta font-mono">
              {{ formatSize(version.fileSize) }} · {{ formatDateFull(version.createdAt) }}
            </div>
          </div>
          <div class="office-version-row__actions">
            <OButton variant="ghost" size="sm" @click="downloadTeamVersion(version.versionNumber)">下载</OButton>
            <OButton v-if="canRestoreTeamVersion" variant="ghost" size="sm" @click="restoreTeamVersion(version.versionNumber)">恢复</OButton>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <span v-if="!canRestoreTeamVersion" class="office-versions__permission">只读权限仅可下载历史版本</span>
      <OButton variant="ghost" @click="versionsOpen = false">关闭</OButton>
    </template>
  </OModal>
</template>

<style scoped>
.preview-box {
  min-height: 320px;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.preview-box--office {
  flex: 1;
  min-height: 0;
  height: 100%;
  align-items: stretch;
}
.preview-box img {
  display: block;
  max-width: 100%;
  max-height: 64vh;
  object-fit: contain;
}
.preview-box iframe {
  width: 100%;
  height: 64vh;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
}
.preview-box__error {
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}
.preview-box__mode {
  margin-right: auto;
  color: var(--text-secondary);
  font-size: 12px;
}
.office-versions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.office-versions__target {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.office-versions__empty,
.office-versions__loading {
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}
.office-versions__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.office-versions__permission {
  margin-right: auto;
  color: var(--text-secondary);
  font-size: 12px;
}
.office-version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-surface);
}
.office-version-row__title {
  font-size: 13px;
  font-weight: 500;
}
.office-version-row__meta {
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 11px;
}
.office-version-row__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
