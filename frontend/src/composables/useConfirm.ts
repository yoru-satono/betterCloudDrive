import { useUIStore } from '@/stores/ui'

export function useConfirm() {
  const ui = useUIStore()

  async function confirm(title: string, message: string): Promise<boolean> {
    return ui.confirm(title, message)
  }

  return { confirm }
}
