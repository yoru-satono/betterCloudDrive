<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'danger' | 'subtle'
  size?: 'sm' | 'md'
  loading?: boolean
  disabled?: boolean
  icon?: boolean
}>(), { variant: 'ghost', size: 'md' })
</script>

<template>
  <button
    class="o-btn"
    :class="[`o-btn--${variant}`, `o-btn--${size}`, { 'o-btn--icon': icon, 'o-btn--loading': loading }]"
    :disabled="disabled || loading"
  >
    <span v-if="loading" class="o-btn__spinner" />
    <slot />
  </button>
</template>

<style scoped>
.o-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid transparent;
  border-radius: 7px;
  font-family: var(--font-sans);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--fast) var(--ease-out);
  white-space: nowrap;
  position: relative;
  overflow: hidden;
}
.o-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.o-btn--md { padding: 7px 14px; font-size: 13px; }
.o-btn--sm { padding: 4px 10px; font-size: 12px; gap: 4px; }
.o-btn--icon { padding: 7px; }
.o-btn--icon.o-btn--sm { padding: 5px; }

.o-btn--primary {
  background: var(--accent);
  color: #080809;
  border-color: var(--accent);
}
.o-btn--primary:not(:disabled):hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
  box-shadow: 0 0 16px var(--accent-glow);
}

.o-btn--ghost {
  background: transparent;
  color: var(--text-secondary);
  border-color: var(--border);
}
.o-btn--ghost:not(:disabled):hover {
  background: var(--bg-elevated);
  color: var(--text-primary);
  border-color: var(--border-hover);
}

.o-btn--subtle {
  background: transparent;
  color: var(--text-secondary);
  border-color: transparent;
}
.o-btn--subtle:not(:disabled):hover {
  background: var(--bg-elevated);
  color: var(--text-primary);
}

.o-btn--danger {
  background: transparent;
  color: var(--danger);
  border-color: rgba(255, 71, 87, 0.25);
}
.o-btn--danger:not(:disabled):hover {
  background: var(--danger-dim);
  border-color: var(--danger);
}

.o-btn__spinner {
  width: 13px;
  height: 13px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
