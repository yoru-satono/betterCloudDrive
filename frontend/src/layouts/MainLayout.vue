<script setup lang="ts">
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import SearchOverlay from '@/components/layout/SearchOverlay.vue'
import TransferQueue from '@/components/file/TransferQueue.vue'
import FilePreviewModal from '@/components/file/FilePreviewModal.vue'
import { onMounted, onUnmounted } from 'vue'
import { useUIStore } from '@/stores/ui'
import { i18n } from '@/i18n'

const ui = useUIStore()
const t = i18n.global.t
function closeDrawerOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') ui.closeMobileNav()
}
onMounted(() => {
  window.addEventListener('keydown', closeDrawerOnEscape)
})
onUnmounted(() => window.removeEventListener('keydown', closeDrawerOnEscape))
</script>

<template>
  <div class="main-layout">
    <button v-if="ui.mobileNavOpen" class="main-layout__scrim" :aria-label="t('nav.closeNavigation')" @click="ui.closeMobileNav" />
    <AppSidebar :class="{ 'sidebar--mobile-open': ui.mobileNavOpen }" />
    <div class="main-layout__right">
      <AppHeader />
      <main class="main-layout__content">
        <RouterView v-slot="{ Component, route }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.fullPath" class="main-layout__page" />
          </Transition>
        </RouterView>
      </main>
    </div>
    <SearchOverlay />
    <FilePreviewModal />
    <TransferQueue />
  </div>
</template>

<style scoped>
.main-layout {
  display: flex;
  height: calc(100vh - var(--desktop-titlebar-h, 0px));
  overflow: hidden;
}
.main-layout__right {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
}
.main-layout__content {
  flex: 1; overflow-y: auto; padding: 20px 24px;
}
.main-layout__page {
  min-height: 100%;
}
.main-layout__scrim { display: none; }
@media (max-width: 719px) {
  .main-layout__content { padding: 16px; }
  .main-layout__scrim {
    display: block; position: fixed; inset: var(--desktop-titlebar-h, 0) 0 0; z-index: 39;
    border: 0; background: rgba(0, 0, 0, .62); backdrop-filter: blur(2px);
  }
  :deep(.sidebar) {
    position: fixed; left: 0; top: var(--desktop-titlebar-h, 0); bottom: 0; z-index: 40;
    width: min(82vw, 280px); transform: translateX(-102%); transition: transform var(--base) var(--ease-out);
  }
  :deep(.sidebar.sidebar--mobile-open) { transform: translateX(0); }
  :deep(.sidebar .fade-enter-active), :deep(.sidebar .fade-leave-active) { transition: none; }
}

.page-enter-active {
  transition: opacity 160ms var(--ease-out), transform 160ms var(--ease-out);
}
.page-leave-active {
  transition: opacity 80ms var(--ease-in-out);
}
.page-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.page-leave-to {
  opacity: 0;
}
</style>
