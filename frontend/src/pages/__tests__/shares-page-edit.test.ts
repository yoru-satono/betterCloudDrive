import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import SharesPage from '@/pages/SharesPage.vue'
import * as sharesApi from '@/api/shares'
import { toast } from 'vue-sonner'
import type { ShareLinkEntity } from '@/types/share'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => ({ confirm: vi.fn(() => Promise.resolve(true)) }),
}))

vi.mock('@/config/runtime', () => ({
  buildShareUrl: vi.fn((code: string) => `http://localhost/share/${code}`),
  resolveShareUrl: vi.fn((share: { shareCode: string; shareUrl?: string | null }) => share.shareUrl || `http://localhost/share/${share.shareCode}`),
}))

vi.mock('@/api/shares', () => ({
  listShares: vi.fn(),
  getShare: vi.fn(),
  getSharePassword: vi.fn(),
  updateShare: vi.fn(),
  deleteShare: vi.fn(),
}))

const listShares = sharesApi.listShares as Mock
const updateShare = sharesApi.updateShare as Mock

function share(patch: Partial<ShareLinkEntity> = {}): ShareLinkEntity {
  return {
    id: 1,
    userId: 2,
    teamId: null,
    fileId: 7,
    shareCode: 'abc123',
    hasPassword: true,
    expireAt: '2026-01-10T00:00:00',
    maxVisits: 5,
    downloadCount: 0,
    visitCount: 1,
    isCanceled: false,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...patch,
  }
}

function page(records: ShareLinkEntity[]) {
  return {
    data: {
      data: {
        records,
        total: records.length,
        page: 1,
        size: 100,
        pages: 1,
      },
    },
  }
}

function mountPage() {
  return mount(SharesPage, {
    global: {
      stubs: {
        OModal: {
          props: ['open', 'title'],
          template: '<section v-if="open" role="dialog" :aria-label="title"><slot /><slot name="footer" /></section>',
        },
        OSpinner: { template: '<span>loading</span>' },
      },
    },
  })
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find(item => item.text().trim() === label)
  expect(found, `button ${label}`).toBeTruthy()
  return found!
}

beforeEach(() => {
  listShares.mockReset().mockResolvedValue(page([share()]))
  updateShare.mockReset().mockResolvedValue({ data: { data: share({ maxVisits: null, expireAt: null, hasPassword: false }) } })
})

describe('SharesPage edit dialog', () => {
  it('submits explicit clear flags for password, visit limit, and expiration', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, '编辑').trigger('click')
    await flushPromises()

    await button(wrapper, '清空访问次数').trigger('click')
    await button(wrapper, '清空过期时间').trigger('click')
    await wrapper.find('input[type="checkbox"]').setValue(true)
    await button(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(updateShare).toHaveBeenCalledWith(1, {
      maxVisits: undefined,
      expireAt: undefined,
      password: undefined,
      clearPassword: true,
      clearExpireAt: true,
      clearMaxVisits: true,
    })
    expect(toast.success).toHaveBeenCalledWith('分享已更新')
  })
})
