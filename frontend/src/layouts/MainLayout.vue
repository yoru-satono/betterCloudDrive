<script setup lang="ts">
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import SearchOverlay from '@/components/layout/SearchOverlay.vue'
import UploadQueue from '@/components/file/UploadQueue.vue'
import FilePreviewModal from '@/components/file/FilePreviewModal.vue'
import { useAuthStore } from '@/stores/auth'
import { onMounted } from 'vue'

const auth = useAuthStore()
onMounted(() => { if (!auth.user) auth.fetchMe() })
</script>

<template>
  <div class="main-layout">
    <AppSidebar />
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
    <UploadQueue />
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
