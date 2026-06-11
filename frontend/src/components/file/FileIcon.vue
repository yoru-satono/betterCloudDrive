<script setup lang="ts">
const props = defineProps<{ mimeType: string | null; fileType: string; size?: number }>()

const iconColor = () => {
  const m = props.mimeType || ''
  if (props.fileType === 'folder') return '#f5a623'
  if (m.startsWith('image/')) return '#a78bfa'
  if (m.startsWith('video/')) return '#60a5fa'
  if (m.startsWith('audio/')) return '#34d399'
  if (m.includes('pdf')) return '#f87171'
  if (m.includes('zip') || m.includes('archive') || m.includes('compressed')) return '#fb923c'
  if (m.includes('word') || m.includes('document')) return '#60a5fa'
  if (m.includes('excel') || m.includes('spreadsheet')) return '#4ade80'
  if (m.includes('powerpoint') || m.includes('presentation')) return '#f97316'
  if (m.startsWith('text/') || m.includes('javascript') || m.includes('json')) return '#94a3b8'
  return '#6b7280'
}

const iconPath = () => {
  const m = props.mimeType || ''
  if (props.fileType === 'folder') return 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z'
  if (m.startsWith('image/')) return 'M4 16l4.6-4.6a2 2 0 012.8 0L16 16m-2-2l1.6-1.6a2 2 0 012.8 0L20 14M14 8h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z'
  if (m.startsWith('video/')) return 'M15 10l4.6-2.7A1 1 0 0121 8.3v7.4a1 1 0 01-1.4.9L15 14M4 8h8a2 2 0 012 2v4a2 2 0 01-2 2H4a2 2 0 01-2-2v-4a2 2 0 012-2z'
  if (m.startsWith('audio/')) return 'M9 19V6l12-3v13M9 19c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm12-3c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2z'
  if (m.includes('pdf')) return 'M7 21h10a2 2 0 002-2V9.4L13.4 4H7a2 2 0 00-2 2v13a2 2 0 002 2zM13 4v5h5M9 17h6M9 13h6M9 9h2'
  return 'M7 21h10a2 2 0 002-2V9.4L13.4 4H7a2 2 0 00-2 2v13a2 2 0 002 2zM13 4v5h5'
}
</script>

<template>
  <svg
    :width="size || 20"
    :height="size || 20"
    viewBox="0 0 24 24"
    fill="none"
    :stroke="iconColor()"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <path :d="iconPath()" />
  </svg>
</template>
