import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProfilePage from '@/pages/ProfilePage.vue'
import PublicSharePage from '@/pages/PublicSharePage.vue'
import AdminUsers from '@/pages/admin/AdminUsers.vue'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'
import * as sharesApi from '@/api/shares'
import * as filesApi from '@/api/files'
import * as adminApi from '@/api/admin'
import { downloadSharedFile, downloadSharedFolderZip } from '@/api/download'
import { toast } from 'vue-sonner'

const routerPush = vi.fn()

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => ({ params: { shareCode: 'share-code' } }),
    useRouter: () => ({ push: routerPush }),
  }
})

vi.mock('@/api/shares', () => ({
  accessShare: vi.fn(),
  listSharedFiles: vi.fn(),
  saveSharedItem: vi.fn(),
  listShares: vi.fn(),
  getShare: vi.fn(),
  getSharePassword: vi.fn(),
  updateShare: vi.fn(),
  deleteShare: vi.fn(),
}))

vi.mock('@/api/files', () => ({
  listFiles: vi.fn(),
}))

vi.mock('@/api/admin', () => ({
  listUsers: vi.fn(),
  updateUserStatus: vi.fn(),
  updateUserQuota: vi.fn(),
}))

vi.mock('@/api/download', () => ({
  downloadSharedFile: vi.fn(),
  downloadSharedFolderZip: vi.fn(),
}))

vi.mock('@/api/auth', () => ({
  updateWebDavSettings: vi.fn(),
}))

const accessShare = sharesApi.accessShare as Mock
const listSharedFiles = sharesApi.listSharedFiles as Mock
const saveSharedItem = sharesApi.saveSharedItem as Mock
const listShares = sharesApi.listShares as Mock
const getShare = sharesApi.getShare as Mock
const getSharePassword = sharesApi.getSharePassword as Mock
const listFiles = filesApi.listFiles as Mock
const listUsers = adminApi.listUsers as Mock
const updateUserQuota = adminApi.updateUserQuota as Mock
const downloadSharedFileMock = downloadSharedFile as Mock
const downloadSharedFolderZipMock = downloadSharedFolderZip as Mock
const updateWebDavSettings = authApi.updateWebDavSettings as Mock

const baseStubs = {
  RouterLink: RouterLinkStub,
  OButton: { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  OInput: { template: '<input />' },
  OModal: { template: '<div v-if="open"><slot /><slot name="footer" /></div>', props: ['open'] },
  OSpinner: { template: '<span>loading</span>' },
  OBadge: { template: '<span><slot /></span>' },
  FileIcon: { template: '<span />' },
}

beforeEach(() => {
  setActivePinia(createPinia())
  accessShare.mockReset()
  listSharedFiles.mockReset()
  saveSharedItem.mockReset()
  listShares.mockReset()
  getShare.mockReset()
  getSharePassword.mockReset()
  listFiles.mockReset()
  listUsers.mockReset()
  updateUserQuota.mockReset()
  downloadSharedFileMock.mockReset()
  downloadSharedFolderZipMock.mockReset()
  updateWebDavSettings.mockReset()
  routerPush.mockReset()
})

describe('downgraded frontend UI', () => {
  it('renders profile info without unsupported email/password forms', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      email: 'alice@example.com',
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 1024,
      storageQuota: 2048,
      webdavEnabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }

    const wrapper = mount(ProfilePage)

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('alice@example.com')
    expect(wrapper.text()).toContain('存储空间')
    expect(wrapper.text()).toContain('WebDAV')
    expect(wrapper.text()).toContain('已关闭')
    expect(wrapper.text()).not.toContain('更新邮箱')
    expect(wrapper.text()).not.toContain('修改密码')
    expect(wrapper.text()).not.toContain('邮箱验证')
    expect(wrapper.text()).not.toContain('发送验证码')
  })

  it('requires a password before enabling WebDAV', async () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      email: 'alice@example.com',
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 1024,
      storageQuota: 2048,
      webdavEnabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }

    const wrapper = mount(ProfilePage)

    await wrapper.findAll('button').find(button => button.text() === '开启 WebDAV')!.trigger('click')

    expect(updateWebDavSettings).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('请设置 WebDAV 独立密码')
  })

  it('can enable and disable WebDAV from profile page', async () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      email: 'alice@example.com',
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 1024,
      storageQuota: 2048,
      webdavEnabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }
    updateWebDavSettings.mockResolvedValueOnce({
      data: { data: { ...auth.user, webdavEnabled: true } },
    })
    updateWebDavSettings.mockResolvedValueOnce({
      data: { data: { ...auth.user, webdavEnabled: false } },
    })

    const wrapper = mount(ProfilePage)

    await wrapper.find('input[type="password"]').setValue('dav-pass')
    await wrapper.findAll('button').find(button => button.text() === '开启 WebDAV')!.trigger('click')
    await flushPromises()
    expect(updateWebDavSettings).toHaveBeenCalledWith(true, 'dav-pass')
    expect(wrapper.text()).toContain('已开启')

    await wrapper.findAll('button').find(button => button.text() === '关闭 WebDAV')!.trigger('click')
    await flushPromises()
    expect(updateWebDavSettings).toHaveBeenCalledWith(false, undefined)
    expect(wrapper.text()).toContain('已关闭')
  })

  it('public share page lists files and can download shared files', async () => {
    accessShare.mockResolvedValue({
      data: { data: { fileId: 1, fileName: 'shared-folder', fileType: 'folder', fileSize: 0 } },
    })
    listSharedFiles.mockResolvedValue({
      data: {
        data: {
          records: [
            {
              id: 2,
              userId: 1,
              parentId: 1,
              fileName: 'child.txt',
              fileType: 'file',
              mimeType: 'text/plain',
              fileSize: 12,
              storagePath: null,
              md5Hash: null,
              isDeleted: false,
              versionCount: 1,
              createdAt: '2026-01-01T00:00:00',
              updatedAt: '2026-01-01T00:00:00',
            },
            {
              id: 3,
              userId: 1,
              parentId: 1,
              fileName: 'docs',
              fileType: 'folder',
              mimeType: null,
              fileSize: 0,
              storagePath: null,
              md5Hash: null,
              isDeleted: false,
              versionCount: 1,
              createdAt: '2026-01-01T00:00:00',
              updatedAt: '2026-01-01T00:00:00',
            },
          ],
        },
      },
    })

    const wrapper = mount(PublicSharePage, { global: { stubs: baseStubs } })
    await flushPromises()

    expect(accessShare).toHaveBeenCalledWith('share-code', undefined)
    expect(listSharedFiles).toHaveBeenCalledWith('share-code', null, 1, 100, undefined)
    expect(wrapper.text()).toContain('BetterCloudDrive')
    expect(wrapper.text()).toContain('shared-folder')
    expect(wrapper.text()).toContain('child.txt')
    expect(wrapper.text()).toContain('下载')

    await wrapper.findAll('button').find(button => button.text() === '下载')!.trigger('click')
    expect(downloadSharedFileMock).toHaveBeenCalledWith('share-code', 2, 'child.txt', undefined)

    await wrapper.findAll('button').find(button => button.text() === '下载文件夹')!.trigger('click')
    expect(downloadSharedFolderZipMock).toHaveBeenCalledWith('share-code', 1, 'shared-folder', undefined)
  })

  it('shares page can load a share password on demand', async () => {
    const { default: SharesPage } = await import('@/pages/SharesPage.vue')
    listShares.mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 1,
            userId: 1,
            fileId: 2,
            shareCode: 'share-code',
            hasPassword: true,
            expireAt: null,
            maxVisits: null,
            downloadCount: 0,
            visitCount: 0,
            isCanceled: false,
            createdAt: '2026-01-01T00:00:00',
            updatedAt: '2026-01-01T00:00:00',
          }],
        },
      },
    })
    getShare.mockResolvedValue({
      data: {
        data: {
          id: 1,
          userId: 1,
          fileId: 2,
          shareCode: 'share-code',
          hasPassword: true,
          expireAt: null,
          maxVisits: null,
          downloadCount: 0,
          visitCount: 0,
          isCanceled: false,
          createdAt: '2026-01-01T00:00:00',
          updatedAt: '2026-01-01T00:00:00',
        },
      },
    })
    getSharePassword.mockResolvedValue({ data: { data: { password: 'abcd' } } })

    const wrapper = mount(SharesPage, { global: { stubs: baseStubs } })
    await flushPromises()

    await wrapper.find('button[aria-label="查看密码"]').trigger('click')
    await flushPromises()

    expect(getSharePassword).toHaveBeenCalledWith(1)
    expect(wrapper.text()).toContain('abcd')
  })

  it('redirects anonymous users to login when saving a shared item', async () => {
    accessShare.mockResolvedValue({
      data: { data: { fileId: 1, fileName: 'shared.txt', fileType: 'file', fileSize: 5 } },
    })
    listSharedFiles.mockResolvedValue({ data: { data: { records: [] } } })

    const wrapper = mount(PublicSharePage, { global: { stubs: baseStubs } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')

    expect(routerPush).toHaveBeenCalledWith('/login')
    expect(listFiles).not.toHaveBeenCalled()
  })

  it('saves a shared list item to the selected folder with password', async () => {
    localStorage.setItem('accessToken', 'token')
    accessShare.mockResolvedValue({
      data: { data: { fileId: 1, fileName: 'shared-folder', fileType: 'folder', fileSize: 0 } },
    })
    listSharedFiles.mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 2,
            userId: 1,
            parentId: 1,
            fileName: 'child.txt',
            fileType: 'file',
            mimeType: 'text/plain',
            fileSize: 12,
            storagePath: null,
            md5Hash: null,
            isDeleted: false,
            versionCount: 1,
            createdAt: '2026-01-01T00:00:00',
            updatedAt: '2026-01-01T00:00:00',
          }],
        },
      },
    })
    listFiles.mockResolvedValueOnce({
      data: {
        data: {
          records: [{
            id: 9,
            userId: 2,
            parentId: null,
            fileName: 'Target',
            fileType: 'folder',
            mimeType: null,
            fileSize: 0,
            storagePath: null,
            md5Hash: null,
            isDeleted: false,
            versionCount: 1,
            createdAt: '2026-01-01T00:00:00',
            updatedAt: '2026-01-01T00:00:00',
          }],
        },
      },
    }).mockResolvedValueOnce({ data: { data: { records: [] } } })
    saveSharedItem.mockResolvedValue({ data: { data: { id: 20 } } })

    const wrapper = mount(PublicSharePage, { global: { stubs: baseStubs } })
    await flushPromises()

    await wrapper.findAll('button').filter(button => button.text() === '保存')[1].trigger('click')
    await flushPromises()
    expect(listFiles).toHaveBeenCalledWith({ parentId: null, page: 1, size: 100 })

    await wrapper.findAll('button').find(button => button.text() === 'Target')!.trigger('click')
    await flushPromises()
    expect(listFiles).toHaveBeenCalledWith({ parentId: 9, page: 1, size: 100 })

    await wrapper.findAll('button').find(button => button.text() === '保存到此处')!.trigger('click')
    await flushPromises()

    expect(saveSharedItem).toHaveBeenCalledWith('share-code', {
      fileId: 2,
      targetParentId: 9,
      password: undefined,
    })
    expect(toast.success).toHaveBeenCalledWith('已保存到我的网盘')
  })

  it('shows readable save conflict errors', async () => {
    localStorage.setItem('accessToken', 'token')
    accessShare.mockResolvedValue({
      data: { data: { fileId: 1, fileName: 'shared.txt', fileType: 'file', fileSize: 5 } },
    })
    listSharedFiles.mockResolvedValue({ data: { data: { records: [] } } })
    listFiles.mockResolvedValue({ data: { data: { records: [] } } })
    saveSharedItem.mockRejectedValue({ response: { data: { code: 409001, message: 'conflict' } } })

    const wrapper = mount(PublicSharePage, { global: { stubs: baseStubs } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '保存到此处')!.trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('目标文件夹已存在同名项目')
  })

  it('admin users page uses status and does not render delete user action', async () => {
    listUsers.mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 1,
            username: 'admin-user',
            email: 'admin@example.com',
            role: 'ROLE_ADMIN',
            status: 1,
            storageUsed: 1024,
            storageQuota: 2048,
            createdAt: '2026-01-01T00:00:00',
            updatedAt: '2026-01-01T00:00:00',
          }],
        },
      },
    })

    const wrapper = mount(AdminUsers, { global: { stubs: baseStubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('admin-user')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).toContain('禁用')
    expect(wrapper.text()).not.toContain('删除')
  })

  it('admin users page validates and submits quota updates in bytes', async () => {
    listUsers.mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 1,
            username: 'quota-user',
            email: 'quota@example.com',
            role: 'ROLE_USER',
            status: 1,
            storageUsed: 1024,
            storageQuota: 10 * 1024 * 1024 * 1024,
            createdAt: '2026-01-01T00:00:00',
            updatedAt: '2026-01-01T00:00:00',
          }],
        },
      },
    })
    updateUserQuota.mockResolvedValue({ data: { data: null } })

    const wrapper = mount(AdminUsers, { global: { stubs: baseStubs } })
    await flushPromises()

    await wrapper.find('.quota-btn').trigger('click')
    const input = wrapper.find('.quota-editor__input')
    await input.setValue('abc')
    await wrapper.find('.quota-editor button').trigger('click')

    expect(updateUserQuota).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('请输入有效的存储配额')

    await input.setValue('2.5')
    await wrapper.find('.quota-editor button').trigger('click')
    await flushPromises()

    expect(updateUserQuota).toHaveBeenCalledWith(1, 2684354560)
    expect(toast.success).toHaveBeenCalledWith('配额已更新')
  })
})
