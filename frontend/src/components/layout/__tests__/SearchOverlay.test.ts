import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SearchOverlay from '@/components/layout/SearchOverlay.vue'
import { useSearchContextStore } from '@/stores/searchContext'
import { useUIStore } from '@/stores/ui'

const route = vi.hoisted(() => ({ name: 'Favorites' as string }))
const push = vi.hoisted(() => vi.fn())
const listFavorites = vi.hoisted(() => vi.fn())
const listShares = vi.hoisted(() => vi.fn())
const listTags = vi.hoisted(() => vi.fn())
const listFilesByTag = vi.hoisted(() => vi.fn())
const listRecycleBin = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

vi.mock('@/api/favorites', () => ({ listFavorites }))
vi.mock('@/api/shares', () => ({ listShares }))
vi.mock('@/api/tags', () => ({ listTags, listFilesByTag }))
vi.mock('@/api/recycle', () => ({ listRecycleBin }))
vi.mock('@/components/file/FileIcon.vue', () => ({ default: { template: '<span />' } }))
vi.mock('@/components/file/FileContextMenu.vue', () => ({ default: { template: '<span />' } }))

const emptyPage = { data: { data: { records: [] } } }

describe('SearchOverlay context routing', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    route.name = 'Favorites'
    push.mockReset()
    listFavorites.mockReset().mockResolvedValue(emptyPage)
    listShares.mockReset().mockResolvedValue(emptyPage)
    listTags.mockReset().mockResolvedValue({ data: { data: [] } })
    listFilesByTag.mockReset().mockResolvedValue(emptyPage)
    listRecycleBin.mockReset().mockResolvedValue(emptyPage)
    useUIStore().openSearch()
  })

  it('searches favorites in favorites context', async () => {
    const wrapper = mount(SearchOverlay, { global: { stubs: { Teleport: true } } })

    await wrapper.get('input').setValue('report')
    await flushPromises()

    expect(listFavorites).toHaveBeenCalledWith(1, 20, 'report')
    expect(listShares).not.toHaveBeenCalled()
  })

  it('searches shares in shares context', async () => {
    route.name = 'Shares'
    const wrapper = mount(SearchOverlay, { global: { stubs: { Teleport: true } } })

    expect(wrapper.get('input').attributes('placeholder')).toBe('搜索分享码、文件 ID、有密码/无密码/有效/已过期...')
    await wrapper.get('input').setValue('abc123')
    await flushPromises()

    expect(listShares).toHaveBeenCalledWith(1, 20, 'abc123')
    expect(listFavorites).not.toHaveBeenCalled()
  })

  it('searches tags and active tag files in tags context', async () => {
    route.name = 'Tags'
    useSearchContextStore().setActiveTag({
      id: 7,
      userId: 1,
      tagName: 'work',
      color: '#00d4aa',
      fileCount: 1,
      createdAt: '2026-01-01T00:00:00',
    })
    const wrapper = mount(SearchOverlay, { global: { stubs: { Teleport: true } } })

    expect(wrapper.get('input').attributes('placeholder')).toBe('搜索标签名称，或「work」下的文件名...')
    await wrapper.get('input').setValue('report')
    await flushPromises()

    expect(listTags).toHaveBeenCalledWith('report')
    expect(listFilesByTag).toHaveBeenCalledWith(7, 1, 20, 'report')
  })

  it('searches recycle bin in recycle context', async () => {
    route.name = 'RecycleBin'
    const wrapper = mount(SearchOverlay, { global: { stubs: { Teleport: true } } })

    await wrapper.get('input').setValue('old')
    await flushPromises()

    expect(listRecycleBin).toHaveBeenCalledWith(1, 20, 'old')
    expect(listFavorites).not.toHaveBeenCalled()
  })
})
