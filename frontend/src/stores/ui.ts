import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUIStore = defineStore('ui', () => {
  const activeWorkspace = ref<'personal' | 'team'>('personal')
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

  function setActiveWorkspace(workspace: 'personal' | 'team') {
    activeWorkspace.value = workspace
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

  return { activeWorkspace, sidebarExpanded, searchOpen, confirmDialog, setActiveWorkspace, toggleSidebar, openSearch, closeSearch, confirm, resolveConfirm }
})
