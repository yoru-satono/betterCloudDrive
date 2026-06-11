<script setup lang="ts">
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import SearchOverlay from '@/components/layout/SearchOverlay.vue'
import UploadQueue from '@/components/file/UploadQueue.vue'
import OConfirmDialog from '@/components/base/OConfirmDialog.vue'
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
        <RouterView v-slot="{ Component }">
          <Transition name="page">
            <component :is="Component" class="page-enter" />
          </Transition>
        </RouterView>
      </main>
    </div>
    <SearchOverlay />
    <UploadQueue />
    <OConfirmDialog />
  </div>
</template>

<style scoped>
.main-layout {
  display: flex; height: 100vh; overflow: hidden;
}
.main-layout__right {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
}
.main-layout__content {
  flex: 1; overflow-y: auto; padding: 20px 24px;
}

.page-enter-active { animation: slide-up 260ms var(--ease-out) both; }
@keyframes slide-up {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
