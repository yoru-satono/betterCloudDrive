<script setup lang="ts">
import type { BreadcrumbItem } from '@/types/file'
import { useFilesStore } from '@/stores/files'

const store = useFilesStore()
</script>

<template>
  <nav class="breadcrumb">
    <template v-for="(item, idx) in store.breadcrumb" :key="item.id ?? 'root'">
      <span v-if="idx > 0" class="breadcrumb__sep">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </span>
      <button
        class="breadcrumb__item"
        :class="{ 'breadcrumb__item--active': idx === store.breadcrumb.length - 1 }"
        @click="store.navigateTo(item)"
      >{{ item.name }}</button>
    </template>
  </nav>
</template>

<style scoped>
.breadcrumb {
  display: flex; align-items: center; gap: 2px;
  flex-wrap: wrap; min-height: 28px;
}
.breadcrumb__sep { color: var(--text-muted); display: flex; align-items: center; }
.breadcrumb__item {
  background: transparent; border: none; cursor: pointer;
  color: var(--text-secondary); font-family: var(--font-sans); font-size: 13px;
  padding: 3px 6px; border-radius: 5px;
  transition: all var(--fast);
}
.breadcrumb__item:hover { background: var(--bg-elevated); color: var(--text-primary); }
.breadcrumb__item--active { color: var(--text-primary); font-weight: 500; cursor: default; }
.breadcrumb__item--active:hover { background: transparent; }
</style>
