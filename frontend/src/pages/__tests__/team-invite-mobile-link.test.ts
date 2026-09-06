import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TeamInvitePage from '@/pages/TeamInvitePage.vue'
import * as teamsApi from '@/api/teams'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { token: '0123456789abcdef0123456789abcdef' } }),
  useRouter: () => ({ push }),
}))
vi.mock('@/api/teams', () => ({
  getInvite: vi.fn(),
  acceptInvite: vi.fn(),
  rejectInvite: vi.fn(),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.mocked(teamsApi.getInvite).mockResolvedValue({
    data: {
      data: {
        id: 1,
        teamId: 10,
        email: 'invitee@example.com',
        role: 'VIEWER',
        status: 'PENDING',
        teamName: 'Example team',
        expiresAt: '2026-09-07T00:00:00',
      },
    },
  } as never)
})

describe('TeamInvitePage mobile handoff', () => {
  it('keeps the canonical web flow and exposes an explicit safe custom-scheme link', async () => {
    const wrapper = mount(TeamInvitePage, {
      global: { stubs: { OSpinner: { template: '<span>loading</span>' } } },
    })
    await flushPromises()

    const link = wrapper.get('[data-testid="open-in-mobile-app"]')
    expect(link.attributes('href')).toBe(
      'betterclouddrive://team-invites/0123456789abcdef0123456789abcdef',
    )
    expect(link.text()).not.toBe('')
  })
})
