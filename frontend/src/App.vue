<script setup lang="ts">
import OConfirmDialog from '@/components/base/OConfirmDialog.vue'
import DesktopTitleBar from '@/components/layout/DesktopTitleBar.vue'
import { isDesktopRuntime } from '@/config/runtime'
import { Toaster } from 'vue-sonner'

const desktopRuntime = isDesktopRuntime()
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--desktop': desktopRuntime }">
    <DesktopTitleBar v-if="desktopRuntime" />
    <div class="app-shell__content">
      <RouterView />
    </div>
    <OConfirmDialog />
    <Toaster
      position="bottom-right"
      :theme="'dark'"
      :toast-options="{
        style: {
          background: 'var(--bg-overlay)',
          border: '1px solid rgba(255,255,255,0.1)',
          color: 'var(--text-primary)',
          fontFamily: 'var(--font-sans)',
          fontSize: '13px',
        }
      }"
    />
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100%;
}

.app-shell--desktop {
  --desktop-titlebar-h: 36px;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-base);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}

.app-shell__content {
  min-height: 100%;
}

.app-shell--desktop .app-shell__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
