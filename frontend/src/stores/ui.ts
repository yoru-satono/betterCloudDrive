import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUIStore = defineStore('ui', () => {
  const sidebarExpanded = ref(true)
  const searchOpen = ref(false)
  const confirmDialog = ref<{
    title: string
    message: string
    resolve: (confirmed: boolean) => void
  } | null>(null)

  function toggleSidebar() {
    sidebarExpanded.value = !sidebarExpanded.value
  }

  function openSearch() { searchOpen.value = true }
  function closeSearch() { searchOpen.value = false }

  function confirm(title: string, message: string): Promise<boolean> {
    return new Promise((resolve) => {
      confirmDialog.value = { title, message, resolve }
    })
  }

  function resolveConfirm(result: boolean) {
    confirmDialog.value?.resolve(result)
    confirmDialog.value = null
  }

  return { sidebarExpanded, searchOpen, confirmDialog, toggleSidebar, openSearch, closeSearch, confirm, resolveConfirm }
})
