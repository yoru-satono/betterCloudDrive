import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { TagEntity } from '@/types/tag'
import { i18n } from '@/i18n'

export type SearchMode = 'files' | 'favorites' | 'shares' | 'tags' | 'recycle'

export interface SearchModeConfig {
  mode: SearchMode
  triggerPlaceholder: string
  inputPlaceholder: string
  emptyText: string
}

function config(mode: SearchMode): SearchModeConfig {
  const t = i18n.global.t
  return { mode, triggerPlaceholder: t(`search.${mode}.trigger`), inputPlaceholder: t(`search.${mode}.input`), emptyText: t(`search.${mode}.empty`) }
}

export function resolveSearchMode(route: RouteLocationNormalizedLoaded): SearchModeConfig {
  if (route.name === 'Favorites') return config('favorites')
  if (route.name === 'Shares') return config('shares')
  if (route.name === 'Tags') return config('tags')
  if (route.name === 'RecycleBin') return config('recycle')
  return config('files')
}

export function getSearchTriggerPlaceholder(config: SearchModeConfig, activeTag?: TagEntity | null) {
  if (config.mode === 'tags' && activeTag) return i18n.global.t('search.tags.activeTrigger', { name: activeTag.tagName })
  return config.triggerPlaceholder
}

export function getSearchInputPlaceholder(config: SearchModeConfig, activeTag?: TagEntity | null) {
  if (config.mode === 'tags' && activeTag) return i18n.global.t('search.tags.activeInput', { name: activeTag.tagName })
  return config.inputPlaceholder
}
