<script setup lang="ts">
import OButton from '@/components/base/OButton.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { downloadFile } from '@/api/download'
import { usePreviewStore } from '@/stores/preview'

const preview = usePreviewStore()
</script>

<template>
  <OModal title="文件预览" :open="preview.open" width="720px" @close="preview.closePreview">
    <div class="preview-box">
      <OSpinner v-if="preview.loading" />
      <div v-else-if="preview.error" class="preview-box__error">{{ preview.error }}</div>
      <img v-else-if="preview.target?.mimeType?.startsWith('image/')" :src="preview.url" :alt="preview.target.fileName" />
      <iframe v-else-if="preview.target?.mimeType === 'application/pdf' || preview.target?.mimeType?.startsWith('text/')" :src="preview.url" />
      <div v-else class="preview-box__error">此类型暂不支持内嵌预览</div>
    </div>
    <template #footer>
      <OButton variant="ghost" @click="preview.closePreview">关闭</OButton>
      <OButton v-if="preview.target" variant="primary" @click="downloadFile(preview.target.id, preview.target.fileName)">下载</OButton>
    </template>
  </OModal>
</template>

<style scoped>
.preview-box {
  min-height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
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
</style>
