import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppHeader from '@/components/layout/AppHeader.vue'
import { useSearchContextStore } from '@/stores/searchContext'

const route = vi.hoisted(() => ({ name: 'Files' as string }))
const push = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

describe('AppHeader search context', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    route.name = 'Files'
    push.mockReset()
  })

  it.each([
    ['Files', '搜索文件...'],
    ['Favorites', '搜索收藏...'],
    ['Shares', '搜索分享码、文件 ID 或状态...'],
    ['Tags', '搜索标签名称...'],
    ['RecycleBin', '搜索回收站...'],
  ])('shows the %s search placeholder', (routeName, placeholder) => {
    route.name = routeName
    const wrapper = mount(AppHeader, {
      global: {
        stubs: {
          OButton: { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.text()).toContain(placeholder)
  })

  it('shows active tag file scope in tags context', () => {
    route.name = 'Tags'
    useSearchContextStore().setActiveTag({
      id: 7,
      userId: 1,
      tagName: 'work',
      color: '#00d4aa',
      fileCount: 1,
      createdAt: '2026-01-01T00:00:00',
    })

    const wrapper = mount(AppHeader, {
      global: {
        stubs: {
          OButton: { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.text()).toContain('搜索标签或「work」下文件...')
  })
})
