<script setup lang="ts">
import type { ContextMenuItem } from '@/composables/useContextMenu'

defineProps<{
  visible: boolean
  x: number
  y: number
  items: ContextMenuItem[]
}>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="ctx">
      <div
        v-if="visible"
        class="ctx-menu"
        role="menu"
        :style="{ left: `${x}px`, top: `${y}px` }"
        @click.stop
      >
        <template v-for="(item, i) in items" :key="i">
          <div v-if="item.divider" class="ctx-menu__divider" />
          <button
            v-else
            class="ctx-menu__item"
            role="menuitem"
            :class="{ 'ctx-menu__item--danger': item.danger }"
            @click="() => { item.action(); emit('close') }"
          >
            {{ item.label }}
          </button>
        </template>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ctx-menu {
  position: fixed; z-index: 300;
  background: var(--bg-overlay);
  border: 1px solid var(--border-hover);
  border-radius: 9px;
  padding: 5px;
  min-width: 168px;
  box-shadow: 0 12px 40px rgba(0,0,0,0.5), 0 1px 0 rgba(255,255,255,0.04) inset;
  backdrop-filter: blur(8px);
}
.ctx-menu__item {
  display: block; width: 100%;
  background: transparent; border: none;
  text-align: left; cursor: pointer;
  padding: 7px 12px;
  border-radius: 5px;
  font-family: var(--font-sans); font-size: 13px;
  color: var(--text-secondary);
  transition: all var(--fast);
}
.ctx-menu__item:hover { background: var(--bg-elevated); color: var(--text-primary); }
.ctx-menu__item--danger { color: var(--danger); }
.ctx-menu__item--danger:hover { background: var(--danger-dim); color: var(--danger); }
.ctx-menu__divider { height: 1px; background: var(--border); margin: 4px 0; }

.ctx-enter-active { transition: all 120ms var(--ease-out); }
.ctx-leave-active { transition: all 80ms; }
.ctx-enter-from { opacity: 0; transform: scale(0.95) translateY(-4px); }
.ctx-leave-to   { opacity: 0; transform: scale(0.97); }
</style>
