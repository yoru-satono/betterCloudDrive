<script setup lang="ts">
import OModal from './OModal.vue'
import OButton from './OButton.vue'
import { useUIStore } from '@/stores/ui'

const ui = useUIStore()
</script>

<template>
  <OModal :open="!!ui.confirmDialog" :title="ui.confirmDialog?.title" @close="ui.resolveConfirm(false)">
    <p style="color: var(--text-secondary); line-height: 1.6;">{{ ui.confirmDialog?.message }}</p>
    <template #footer>
      <OButton variant="ghost" @click="ui.resolveConfirm(false)">取消</OButton>
      <OButton variant="danger" @click="ui.resolveConfirm(true)">确认</OButton>
    </template>
  </OModal>
</template>
