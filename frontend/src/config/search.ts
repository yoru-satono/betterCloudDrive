import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { TagEntity } from '@/types/tag'

export type SearchMode = 'files' | 'favorites' | 'shares' | 'tags' | 'recycle'

export interface SearchModeConfig {
  mode: SearchMode
  triggerPlaceholder: string
  inputPlaceholder: string
  emptyText: string
}

const configs: Record<SearchMode, SearchModeConfig> = {
  files: {
    mode: 'files',
    triggerPlaceholder: '搜索文件...',
    inputPlaceholder: '搜索文件名...',
    emptyText: '未找到相关文件',
  },
  favorites: {
    mode: 'favorites',
    triggerPlaceholder: '搜索收藏...',
    inputPlaceholder: '搜索收藏文件名...',
    emptyText: '未找到相关收藏',
  },
  shares: {
    mode: 'shares',
    triggerPlaceholder: '搜索分享码、文件 ID 或状态...',
    inputPlaceholder: '搜索分享码、文件 ID、有密码/无密码/有效/已过期...',
    emptyText: '未找到相关分享',
  },
  tags: {
    mode: 'tags',
    triggerPlaceholder: '搜索标签名称...',
    inputPlaceholder: '搜索标签名称...',
    emptyText: '未找到相关标签或文件',
  },
  recycle: {
    mode: 'recycle',
    triggerPlaceholder: '搜索回收站...',
    inputPlaceholder: '搜索回收站文件名...',
    emptyText: '未找到相关回收站文件',
  },
}

export function resolveSearchMode(route: RouteLocationNormalizedLoaded): SearchModeConfig {
  if (route.name === 'Favorites') return configs.favorites
  if (route.name === 'Shares') return configs.shares
  if (route.name === 'Tags') return configs.tags
  if (route.name === 'RecycleBin') return configs.recycle
  return configs.files
}

export function getSearchTriggerPlaceholder(config: SearchModeConfig, activeTag?: TagEntity | null) {
  if (config.mode === 'tags' && activeTag) return `搜索标签或「${activeTag.tagName}」下文件...`
  return config.triggerPlaceholder
}

export function getSearchInputPlaceholder(config: SearchModeConfig, activeTag?: TagEntity | null) {
  if (config.mode === 'tags' && activeTag) return `搜索标签名称，或「${activeTag.tagName}」下的文件名...`
  return config.inputPlaceholder
}
