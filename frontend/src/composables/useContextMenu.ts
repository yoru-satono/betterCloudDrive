import { ref, onUnmounted } from 'vue'

export interface ContextMenuItem {
  label: string
  icon?: string
  action: () => void
  danger?: boolean
  divider?: boolean
}

export function useContextMenu() {
  const visible = ref(false)
  const x = ref(0)
  const y = ref(0)
  const items = ref<ContextMenuItem[]>([])

  function open(event: MouseEvent, menuItems: ContextMenuItem[]) {
    event.preventDefault()
    event.stopPropagation()
    items.value = menuItems
    visible.value = true

    const W = window.innerWidth
    const H = window.innerHeight
    const menuW = 200
    const menuH = menuItems.length * 36 + 16
    const margin = 8

    const nextX = event.clientX + menuW > W ? event.clientX - menuW : event.clientX
    const nextY = event.clientY + menuH > H ? event.clientY - menuH : event.clientY
    x.value = Math.min(Math.max(nextX, margin), Math.max(W - menuW - margin, margin))
    y.value = Math.min(Math.max(nextY, margin), Math.max(H - menuH - margin, margin))
  }

  function close() {
    visible.value = false
  }

  const handler = () => close()
  window.addEventListener('click', handler, { capture: true })
  window.addEventListener('contextmenu', handler, { capture: true })
  onUnmounted(() => {
    window.removeEventListener('click', handler, { capture: true })
    window.removeEventListener('contextmenu', handler, { capture: true })
  })

  return { visible, x, y, items, open, close }
}
