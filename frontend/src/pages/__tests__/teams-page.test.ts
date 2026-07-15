import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TeamsPage from '@/pages/TeamsPage.vue'
import * as teamsApi from '@/api/teams'

const routerPush = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/api/teams', () => ({
  listAllTeams: vi.fn(),
  createTeam: vi.fn(),
}))

describe('TeamsPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    routerPush.mockReset()
    vi.mocked(teamsApi.listAllTeams).mockResolvedValue([{
      id: 10,
      name: '产品组',
      ownerId: 1,
      storageQuota: 1024,
      storageUsed: 256,
      role: 'OWNER',
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }])
  })

  it('lists teams without embedding a workspace switch in page content', async () => {
    const wrapper = mount(TeamsPage, {
      global: {
        stubs: {
          OModal: { template: '<div />' },
          OSpinner: { template: '<span />' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('产品组')
    expect(wrapper.find('[data-testid="open-personal-drive"]').exists()).toBe(false)
  })
})
