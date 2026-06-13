<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

defineOptions({ inheritAttrs: false })
const props = defineProps<{ title?: string; open: boolean; width?: string }>()
const emit = defineEmits<{ close: [] }>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) emit('close')
}

watch(
  () => props.open,
  open => {
    if (open) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  },
  { immediate: true },
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="o-modal-overlay" v-bind="$attrs" @click.self="emit('close')">
        <div
          class="o-modal"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="{ width: props.width || '440px' }"
        >
          <div v-if="title" class="o-modal__header">
            <span class="o-modal__title">{{ title }}</span>
            <button class="o-modal__close" type="button" aria-label="关闭" @click="emit('close')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="o-modal__body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="o-modal__footer">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.o-modal-overlay {
  position: fixed; inset: 0; z-index: 200;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
}
.o-modal {
  background: var(--bg-elevated);
  border: 1px solid var(--border-hover);
  border-radius: 12px;
  max-width: 96vw;
  max-height: 90vh;
  display: flex; flex-direction: column;
  box-shadow: 0 24px 80px rgba(0,0,0,0.6);
}
.o-modal__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 0;
}
.o-modal__title { font-size: 15px; font-weight: 600; }
.o-modal__close {
  width: 28px; height: 28px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--fast);
}
.o-modal__close:hover { background: var(--bg-overlay); color: var(--text-primary); }
.o-modal__body { padding: 20px; overflow-y: auto; }
.o-modal__footer {
  padding: 12px 20px;
  border-top: 1px solid var(--border);
  display: flex; justify-content: flex-end; gap: 8px;
}

.modal-enter-active { transition: all 200ms var(--ease-out); }
.modal-leave-active { transition: all 150ms var(--ease-in-out); }
.modal-enter-from  { opacity: 0; }
.modal-leave-to    { opacity: 0; }
.modal-enter-from .o-modal  { transform: scale(0.96) translateY(8px); }
.modal-leave-to   .o-modal  { transform: scale(0.98); }
.o-modal { transition: transform 200ms var(--ease-out); }
</style>
