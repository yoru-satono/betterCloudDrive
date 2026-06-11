<template>
  <div class="o-spinner" :class="`o-spinner--${size || 'md'}`">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <circle cx="12" cy="12" r="10" class="track" />
      <path d="M12 2a10 10 0 0 1 10 10" class="arc" />
    </svg>
  </div>
</template>

<script setup lang="ts">
defineProps<{ size?: 'sm' | 'md' | 'lg' }>()
</script>

<style scoped>
.o-spinner { display: inline-flex; animation: spin 0.8s linear infinite; color: var(--accent); }
.o-spinner--sm svg { width: 16px; height: 16px; }
.o-spinner--md svg { width: 24px; height: 24px; }
.o-spinner--lg svg { width: 40px; height: 40px; }
.track { stroke: var(--border-hover); }
.arc { stroke: var(--accent); }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
