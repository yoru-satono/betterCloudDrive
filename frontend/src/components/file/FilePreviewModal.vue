<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OnlyOfficeEditor from '@/components/file/OnlyOfficeEditor.vue'
import { downloadFile, downloadTeamFile } from '@/api/download'
import { usePreviewStore } from '@/stores/preview'

const preview = usePreviewStore()

function downloadPreviewTarget() {
  if (!preview.target) return
  if (preview.teamId) {
    downloadTeamFile(preview.teamId, preview.target.id, preview.target.fileName)
    return
  }
  downloadFile(preview.target.id, preview.target.fileName)
}
</script>

<template>
  <OModal
    title="文件预览"
    :open="preview.open"
    :width="preview.officeMode ? 'calc(100vw - 32px)' : '720px'"
    :height="preview.officeMode ? 'calc(100vh - 32px)' : undefined"
    :body-class="preview.officeMode ? 'o-modal__body--fill' : undefined"
    @close="preview.closePreview"
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
      <OButton variant="ghost" @click="preview.closePreview">关闭</OButton>
      <OButton v-if="preview.target" variant="primary" @click="downloadPreviewTarget">下载</OButton>
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
</style>
