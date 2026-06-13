<script setup lang="ts">
import OConfirmDialog from '@/components/base/OConfirmDialog.vue'
import { Toaster } from 'vue-sonner'
</script>

<template>
  <RouterView />
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
</template>
