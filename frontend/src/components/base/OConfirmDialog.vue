<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import OModal from './OModal.vue'
import OButton from './OButton.vue'
import { useUIStore } from '@/stores/ui'

const ui = useUIStore()
</script>

<template>
  <OModal
    :open="!!ui.confirmDialog"
    :title="ui.confirmDialog?.title"
    style="z-index: 300"
    @close="ui.resolveConfirm(false)"
  >
    <p style="color: var(--text-secondary); line-height: 1.6;">{{ ui.confirmDialog?.message }}</p>
    <template #footer>
      <OButton variant="ghost" @click="ui.resolveConfirm(false)">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
      <OButton variant="danger" @click="ui.resolveConfirm(true)">{{ __t('commonUi.oconfirmdialog.confirm') }}</OButton>
    </template>
  </OModal>
</template>
