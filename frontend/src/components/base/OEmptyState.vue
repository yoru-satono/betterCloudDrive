<script setup lang="ts">
defineProps<{ icon?: string; title: string; description?: string }>()
</script>

<template>
  <div class="o-empty">
    <div class="o-empty__icon">
      <slot name="icon">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M3 7V5a2 2 0 012-2h2M17 3h2a2 2 0 012 2v2M21 17v2a2 2 0 01-2 2h-2M7 21H5a2 2 0 01-2-2v-2"/>
          <circle cx="12" cy="12" r="3"/>
        </svg>
      </slot>
    </div>
    <p class="o-empty__title">{{ title }}</p>
    <p v-if="description" class="o-empty__desc">{{ description }}</p>
    <slot />
  </div>
</template>

<style scoped>
.o-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 60px 20px; color: var(--text-secondary);
}
.o-empty__icon { color: var(--text-muted); opacity: 0.6; }
.o-empty__title { font-size: 14px; font-weight: 500; color: var(--text-secondary); }
.o-empty__desc { font-size: 12px; color: var(--text-muted); text-align: center; max-width: 280px; }
</style>
