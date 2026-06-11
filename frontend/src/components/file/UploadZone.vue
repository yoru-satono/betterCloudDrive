<template>
  <Transition name="fade">
    <div v-if="isDragging" class="upload-zone">
      <div class="upload-zone__inner">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
        </svg>
        <span>松开鼠标上传文件</span>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
defineProps<{ isDragging: boolean }>()
</script>

<style scoped>
.upload-zone {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(0, 212, 170, 0.06);
  border: 2px dashed var(--accent);
  display: flex; align-items: center; justify-content: center;
  pointer-events: none;
  backdrop-filter: blur(2px);
}
.upload-zone__inner {
  display: flex; flex-direction: column; align-items: center; gap: 14px;
  color: var(--accent); font-size: 16px; font-weight: 500;
}
</style>
