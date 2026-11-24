<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { i18n, locale } from '@/i18n'
import type { OnlyOfficeConfigResponse, OnlyOfficeDocEditor } from '@/types/office'
import { identityRevision } from '../identity'
import { loadOfficeScript } from '../officeScript'

const props = defineProps<{ officeConfig: OnlyOfficeConfigResponse | null }>()
const error = ref('')
const editorElementId = `qt-office-${crypto.randomUUID()}`
let editor: OnlyOfficeDocEditor | null = null, revision = 0, disposed = false
let resizeTimer: ReturnType<typeof setTimeout> | undefined
function destroy() { editor?.destroyEditor?.(); editor = null; clearTimeout(resizeTimer) }
async function initialize() {
  const request = ++revision, identity = identityRevision(), config = props.officeConfig
  destroy()
  error.value = ''
  if (!config || disposed) return
  try {
    const Constructor = await loadOfficeScript(config.documentServerUrl)
    await nextTick()
    if (disposed || revision !== request || identity !== identityRevision()) return
    editor = new Constructor(editorElementId, { ...config.config, width: '100%', height: '100%' })
    resizeTimer = setTimeout(() => { if (!disposed && request === revision) window.dispatchEvent(new Event('resize')) }, 200)
  } catch {
    if (!disposed && request === revision && identity === identityRevision()) error.value = i18n.global.t('file.onlyofficeeditor.onlyofficeFailedToLoad')
  }
}
watch(() => props.officeConfig, initialize, { immediate: true })
function invalidate() { revision++; destroy() }
window.addEventListener('bcd:identity-invalidated', invalidate)
onBeforeUnmount(() => { disposed = true; invalidate(); window.removeEventListener('bcd:identity-invalidated', invalidate) })
</script>
<template>
  <div class="onlyoffice-shell" data-testid="onlyoffice-shell">
    <div v-if="error" class="onlyoffice-shell__error">
      {{ error }} <button type="button" @click="initialize">{{ locale.startsWith('zh') ? '重新加载' : 'Retry' }}</button>
    </div>
    <div :id="editorElementId" class="onlyoffice-shell__editor" data-testid="onlyoffice-editor-container" />
  </div>
</template>
<style scoped>
.onlyoffice-shell { position: relative; flex: 1; width: 100%; height: 100%; min-height: 0; }
.onlyoffice-shell__editor { position: absolute; inset: 0; width: 100%; height: 100%; }
.onlyoffice-shell__error { position: relative; z-index: 1; padding: 24px; color: var(--text-secondary); text-align: center; }
button { margin-left: 12px; color: var(--accent); background: transparent; border: 1px solid var(--border); border-radius: 6px; padding: 6px 12px; cursor: pointer; }
</style>
