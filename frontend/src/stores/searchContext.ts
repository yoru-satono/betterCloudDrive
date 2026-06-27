import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TagEntity } from '@/types/tag'

export const useSearchContextStore = defineStore('searchContext', () => {
  const activeTag = ref<TagEntity | null>(null)

  function setActiveTag(tag: TagEntity | null) {
    activeTag.value = tag
  }

  return { activeTag, setActiveTag }
})
