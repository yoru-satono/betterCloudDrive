<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { onBeforeUnmount, ref, watch } from 'vue'
import type { OnlyOfficeConfigResponse, OnlyOfficeDocEditor, OnlyOfficeWindow } from '@/types/office'

const props = defineProps<{
  officeConfig: OnlyOfficeConfigResponse | null
}>()

const error = ref('')
let editor: OnlyOfficeDocEditor | null = null
const editorElementId = `onlyoffice-editor-${Math.random().toString(36).slice(2)}`

watch(
  () => props.officeConfig,
  async (config) => {
    destroyEditor()
    error.value = ''
    if (!config) return
    try {
      await loadOnlyOfficeScript(config.documentServerUrl)
      const win = window as OnlyOfficeWindow
      if (!win.DocsAPI?.DocEditor) throw new Error('OnlyOffice DocsAPI is unavailable')
      editor = new win.DocsAPI.DocEditor(editorElementId, {
        ...config.config,
        width: '100%',
        height: '100%',
      })
      queueEditorResize()
    } catch (e) {
      error.value = e instanceof Error ? e.message : __t('file.onlyofficeeditor.onlyofficeFailedToLoad')
    }
  },
  { immediate: true },
)

onBeforeUnmount(destroyEditor)

function destroyEditor() {
  editor?.destroyEditor?.()
  editor = null
}

function queueEditorResize() {
  requestAnimationFrame(() => {
    window.dispatchEvent(new Event('resize'))
    window.setTimeout(() => window.dispatchEvent(new Event('resize')), 200)
  })
}

function loadOnlyOfficeScript(documentServerUrl: string) {
  const src = `${documentServerUrl.replace(/\/+$/, '')}/web-apps/apps/api/documents/api.js`
  const existing = document.querySelector<HTMLScriptElement>(`script[data-onlyoffice-api="${src}"]`)
  if (existing) {
    return existing.dataset.loaded === 'true'
      ? Promise.resolve()
      : new Promise<void>((resolve, reject) => {
          existing.addEventListener('load', () => resolve(), { once: true })
          existing.addEventListener('error', () => reject(new Error('OnlyOffice API script failed to load')), { once: true })
        })
  }

  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.async = true
    script.dataset.onlyofficeApi = src
    script.addEventListener('load', () => {
      script.dataset.loaded = 'true'
      resolve()
    }, { once: true })
    script.addEventListener('error', () => reject(new Error('OnlyOffice API script failed to load')), { once: true })
    document.head.appendChild(script)
  })
}
</script>

<template>
  <div class="onlyoffice-shell" data-testid="onlyoffice-shell">
    <div v-if="error" class="onlyoffice-shell__error">{{ error }}</div>
    <div :id="editorElementId" class="onlyoffice-shell__editor" data-testid="onlyoffice-editor-container" />
  </div>
</template>

<style scoped>
.onlyoffice-shell {
  position: relative;
  flex: 1;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.onlyoffice-shell__editor {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.onlyoffice-shell__error {
  position: relative;
  z-index: 1;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}
</style>
