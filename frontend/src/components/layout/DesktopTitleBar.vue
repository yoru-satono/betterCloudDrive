<script setup lang="ts">
import { getCurrentWindow, type Window as TauriWindow } from '@tauri-apps/api/window'
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { Settings, Minus, Square, X } from 'lucide-vue-next'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OModal from '@/components/base/OModal.vue'
import DesktopSettingsPanel from '@/components/layout/DesktopSettingsPanel.vue'
import { useAuthStore } from '@/stores/auth'
import {
  getApiBaseUrl,
  getWebBaseUrl,
  setStoredApiBaseUrl,
  setStoredWebBaseUrl,
} from '@/config/runtime'
import { toast } from 'vue-sonner'

const auth = useAuthStore()
const showConnectionSettings = ref(false)
const showClientSettings = ref(false)
const apiBaseUrl = ref(getApiBaseUrl())
const webBaseUrl = ref(getWebBaseUrl())
const settingsPanel = ref<HTMLElement | null>(null)
const settingsButton = ref<HTMLButtonElement | null>(null)
const isLoggedIn = computed(() => !!auth.user || !!localStorage.getItem('accessToken'))
const settingsLabel = computed(() => isLoggedIn.value ? '客户端设置' : '连接设置')

function getDesktopWindow(): TauriWindow {
  return getCurrentWindow()
}

function syncConnectionSettings() {
  apiBaseUrl.value = getApiBaseUrl()
  webBaseUrl.value = getWebBaseUrl()
}

async function toggleConnectionSettings() {
  if (!showConnectionSettings.value) syncConnectionSettings()
  showConnectionSettings.value = !showConnectionSettings.value
  if (showConnectionSettings.value) {
    await nextTick()
    settingsPanel.value?.querySelector<HTMLInputElement>('input')?.focus()
  }
}

async function handleSettingsClick() {
  if (isLoggedIn.value) {
    closeConnectionSettings()
    showClientSettings.value = true
    return
  }

  await toggleConnectionSettings()
}

function closeConnectionSettings() {
  showConnectionSettings.value = false
}

function saveConnectionSettings() {
  setStoredApiBaseUrl(apiBaseUrl.value)
  setStoredWebBaseUrl(webBaseUrl.value)
  closeConnectionSettings()
  toast.success('连接设置已保存')
}

function onDocumentPointerDown(event: PointerEvent) {
  if (!showConnectionSettings.value) return
  const target = event.target as Node | null
  if (target && (settingsPanel.value?.contains(target) || settingsButton.value?.contains(target))) return
  closeConnectionSettings()
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeConnectionSettings()
}

async function minimizeWindow() {
  await getDesktopWindow().minimize()
}

async function toggleMaximizeWindow() {
  await getDesktopWindow().toggleMaximize()
}

async function closeWindow() {
  await getDesktopWindow().close()
}

async function startWindowDrag(event: PointerEvent) {
  if (event.button !== 0) return
  await getDesktopWindow().startDragging().catch(() => undefined)
}

document.addEventListener('pointerdown', onDocumentPointerDown)
document.addEventListener('keydown', onDocumentKeydown)

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <header class="desktop-titlebar" data-testid="desktop-titlebar" data-tauri-drag-region>
    <div class="desktop-titlebar__brand" data-tauri-drag-region @pointerdown="startWindowDrag">
      <span class="desktop-titlebar__mark" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" />
          <polyline points="9 22 9 12 15 12 15 22" />
        </svg>
      </span>
      <span class="desktop-titlebar__title">BetterCloudDrive</span>
    </div>
    <div class="desktop-titlebar__controls" aria-label="窗口控制">
      <div class="desktop-titlebar__settings">
        <button
          ref="settingsButton"
          class="desktop-titlebar__button"
          type="button"
          :aria-label="settingsLabel"
          :title="settingsLabel"
          :aria-expanded="isLoggedIn ? showClientSettings : showConnectionSettings"
          :aria-controls="isLoggedIn ? 'desktop-client-settings' : 'desktop-connection-settings'"
          @click="handleSettingsClick"
        >
          <Settings :size="14" :stroke-width="1.8" aria-hidden="true" />
        </button>
        <form
          v-if="showConnectionSettings && !isLoggedIn"
          id="desktop-connection-settings"
          ref="settingsPanel"
          class="connection-settings"
          aria-label="连接设置"
          @submit.prevent="saveConnectionSettings"
        >
          <div class="connection-settings__head">
            <strong>连接设置</strong>
            <span>注册、找回密码、分享链接都会使用这里的地址。</span>
          </div>
          <OInput
            v-model="apiBaseUrl"
            label="API 地址"
            placeholder="http://127.0.0.1:8080/api/v1"
            autocomplete="off"
          />
          <OInput
            v-model="webBaseUrl"
            label="Web 分享地址"
            placeholder="http://127.0.0.1:3000"
            autocomplete="off"
          />
          <div class="connection-settings__actions">
            <OButton type="button" variant="ghost" size="sm" @click="closeConnectionSettings">取消</OButton>
            <OButton type="submit" variant="primary" size="sm">保存</OButton>
          </div>
        </form>
      </div>
      <button class="desktop-titlebar__button" type="button" aria-label="最小化" title="最小化" @click="minimizeWindow">
        <Minus :size="14" :stroke-width="1.8" aria-hidden="true" />
      </button>
      <button
        class="desktop-titlebar__button"
        type="button"
        aria-label="最大化或还原"
        title="最大化或还原"
        @click="toggleMaximizeWindow"
      >
        <Square :size="12" :stroke-width="1.8" aria-hidden="true" />
      </button>
      <button
        class="desktop-titlebar__button desktop-titlebar__button--close"
        type="button"
        aria-label="关闭"
        title="关闭"
        @click="closeWindow"
      >
        <X :size="14" :stroke-width="1.8" aria-hidden="true" />
      </button>
    </div>
    <OModal
      id="desktop-client-settings"
      title="客户端设置"
      :open="showClientSettings"
      width="760px"
      @close="showClientSettings = false"
    >
      <DesktopSettingsPanel v-if="showClientSettings" />
    </OModal>
  </header>
</template>

<style scoped>
.desktop-titlebar {
  height: var(--desktop-titlebar-h, 36px);
  min-height: var(--desktop-titlebar-h, 36px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  user-select: none;
}

.desktop-titlebar__brand {
  min-width: 0;
  flex: 1;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  color: var(--text-secondary);
}

.desktop-titlebar__mark {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--accent);
  background: var(--accent-dim);
  border: 1px solid rgba(0, 212, 170, 0.16);
  border-radius: 6px;
}

.desktop-titlebar__title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 600;
}

.desktop-titlebar__controls {
  height: 100%;
  display: flex;
  align-items: stretch;
  flex-shrink: 0;
}

.desktop-titlebar__settings {
  position: relative;
  height: 100%;
  display: flex;
  align-items: stretch;
}

.desktop-titlebar__button {
  width: 44px;
  height: 100%;
  border: 0;
  border-left: 1px solid transparent;
  background: transparent;
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition:
    background var(--fast),
    color var(--fast);
}

.desktop-titlebar__button:hover {
  background: var(--bg-elevated);
  color: var(--text-primary);
}

.desktop-titlebar__button:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

.desktop-titlebar__button--close:hover {
  background: var(--danger);
  color: #fff;
}

.connection-settings {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 60;
  width: min(360px, calc(100vw - 32px));
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-hover);
  border-radius: 8px;
  background: var(--bg-elevated);
  box-shadow: 0 18px 60px rgba(0, 0, 0, 0.45);
  user-select: text;
}

.connection-settings__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.connection-settings__head strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.connection-settings__head span {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.connection-settings__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
