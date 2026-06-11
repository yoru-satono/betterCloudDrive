<script setup lang="ts">
defineProps<{ variant?: 'default' | 'accent' | 'danger' | 'warning' | 'success' }>()
</script>

<template>
  <span class="o-badge" :class="`o-badge--${variant || 'default'}`"><slot /></span>
</template>

<style scoped>
.o-badge {
  display: inline-flex; align-items: center;
  font-size: 11px; font-weight: 500; padding: 2px 7px; border-radius: 4px; letter-spacing: 0.03em;
}
.o-badge--default  { background: var(--bg-overlay); color: var(--text-secondary); border: 1px solid var(--border); }
.o-badge--accent   { background: var(--accent-dim); color: var(--accent); border: 1px solid rgba(0,212,170,0.2); }
.o-badge--danger   { background: var(--danger-dim); color: var(--danger); border: 1px solid rgba(255,71,87,0.2); }
.o-badge--warning  { background: rgba(255,165,2,0.1); color: var(--warning); border: 1px solid rgba(255,165,2,0.2); }
.o-badge--success  { background: rgba(46,213,115,0.1); color: var(--success); border: 1px solid rgba(46,213,115,0.2); }
</style>
