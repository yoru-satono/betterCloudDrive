import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PublicLayout from '@/layouts/PublicLayout.vue'
import LoginPage from '@/pages/auth/LoginPage.vue'
import RegisterPage from '@/pages/auth/RegisterPage.vue'
import PublicSharePage from '@/pages/PublicSharePage.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useAuthStore } from '@/stores/auth'
import * as sharesApi from '@/api/shares'

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => ({ path: '/files', params: { shareCode: 'share-code' }, query: {} }),
    useRouter: () => ({ push: vi.fn() }),
  }
})

vi.mock('@/api/shares', () => ({
  accessShare: vi.fn(),
  listSharedFiles: vi.fn(),
  saveSharedItem: vi.fn(),
}))

const accessShare = sharesApi.accessShare as Mock
const listSharedFiles = sharesApi.listSharedFiles as Mock

const stubs = {
  RouterLink: RouterLinkStub,
  RouterView: { template: '<main />' },
  OButton: { template: '<button><slot /></button>' },
  OInput: { template: '<input />' },
  OSpinner: { template: '<span />' },
  FileIcon: { template: '<span />' },
}

beforeEach(() => {
  setActivePinia(createPinia())
  accessShare.mockReset()
  listSharedFiles.mockReset()
})

describe('BetterCloudDrive brand text', () => {
  it('renders the full brand in the public layout', () => {
    const wrapper = mount(PublicLayout, { global: { stubs } })
    expect(wrapper.text()).toContain('BetterCloudDrive')
    expect(wrapper.text()).not.toContain('BetterDrive')
  })

  it('renders the full brand in auth pages', () => {
    const login = mount(LoginPage, { global: { stubs } })
    const register = mount(RegisterPage, { global: { stubs } })

    expect(login.text()).toContain('登录到 BetterCloudDrive')
    expect(register.text()).toContain('加入 BetterCloudDrive')
    expect(`${login.text()} ${register.text()}`).not.toContain('BetterDrive')
  })

  it('renders the full brand in public share page', async () => {
    accessShare.mockResolvedValue({
      data: { data: { fileId: 1, fileName: 'shared.txt', fileType: 'file', fileSize: 5 } },
    })
    listSharedFiles.mockResolvedValue({ data: { data: { records: [] } } })

    const wrapper = mount(PublicSharePage, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('BetterCloudDrive')
    expect(wrapper.text()).not.toContain('BetterDrive')
  })

  it('renders the full brand in the expanded sidebar', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      email: null,
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 0,
      storageQuota: 1024,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }

    const wrapper = mount(AppSidebar, { global: { stubs } })

    expect(wrapper.text()).toContain('BetterCloudDrive')
    expect(wrapper.text()).not.toContain('BetterDrive')
    expect(wrapper.text()).not.toContain('设置')
  })
})
