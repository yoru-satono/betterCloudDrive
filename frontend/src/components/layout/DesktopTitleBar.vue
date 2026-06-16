<script setup lang="ts">
import { getCurrentWindow, type Window as TauriWindow } from '@tauri-apps/api/window'
import { Minus, Square, X } from 'lucide-vue-next'

function getDesktopWindow(): TauriWindow {
  return getCurrentWindow()
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
</style>
