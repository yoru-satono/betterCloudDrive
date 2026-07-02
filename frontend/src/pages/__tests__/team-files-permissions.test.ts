import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TeamFilesPage from '@/pages/TeamFilesPage.vue'
import { useAuthStore } from '@/stores/auth'
import * as teamsApi from '@/api/teams'
import { toast } from 'vue-sonner'
import type { FileEntity } from '@/types/file'
import type { TeamEffectivePermission } from '@/types/team'

const routerPush = vi.fn()

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { teamId: '10' } }),
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/api/teams', () => ({
  listTeams: vi.fn(),
  getTeam: vi.fn(),
  listTeamFiles: vi.fn(),
  getTeamFileEffectivePermission: vi.fn(),
  listTeamFilePermissions: vi.fn(),
  listMembers: vi.fn(),
  upsertTeamFilePermission: vi.fn(),
  deleteTeamFilePermission: vi.fn(),
}))

vi.mock('@/api/download', () => ({
  downloadTeamFile: vi.fn(),
  downloadTeamFolderZip: vi.fn(),
}))

vi.mock('@/api/desktopUpload', () => ({
  registerDesktopUploadListeners: vi.fn(),
  uploadDesktopFiles: vi.fn(),
  uploadDesktopFolder: vi.fn(),
}))

vi.mock('@/api/versions', () => ({
  listTeamVersions: vi.fn(),
  deleteTeamVersion: vi.fn(),
  downloadTeamVersion: vi.fn(),
  restoreTeamVersion: vi.fn(),
}))

vi.mock('@/config/runtime', () => ({
  getApiBaseUrl: vi.fn(() => '/api/v1'),
  getWebBaseUrl: vi.fn(() => 'http://localhost:3000'),
  normalizeBaseUrl: vi.fn((value: string) => value.trim().replace(/\/+$/, '')),
  setStoredApiBaseUrl: vi.fn(),
  setStoredWebBaseUrl: vi.fn(),
  buildShareUrl: vi.fn(() => 'http://localhost/share/mock'),
  isDesktopRuntime: vi.fn(() => false),
}))

const listTeams = teamsApi.listTeams as Mock
const getTeam = teamsApi.getTeam as Mock
const listTeamFiles = teamsApi.listTeamFiles as Mock
const getTeamFileEffectivePermission = teamsApi.getTeamFileEffectivePermission as Mock
const listTeamFilePermissions = teamsApi.listTeamFilePermissions as Mock
const listMembers = teamsApi.listMembers as Mock
const upsertTeamFilePermission = teamsApi.upsertTeamFilePermission as Mock

const file = (id: number, fileName: string): FileEntity => ({
  id,
  userId: 1,
  parentId: null,
  fileName,
  fileType: 'file',
  mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  fileSize: 1,
  storagePath: null,
  md5Hash: null,
  isDeleted: false,
  versionCount: 1,
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-01T00:00:00',
})

const permission = (patch: Partial<TeamEffectivePermission>): TeamEffectivePermission => ({
  canRead: true,
  canWrite: true,
  canShare: true,
  canDelete: true,
  canManage: true,
  sourceChain: [],
  ...patch,
})

const stubs = {
  OButton: { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  OInput: { template: '<input v-bind="$attrs" />' },
  OModal: { template: '<section v-if="open"><slot /><slot name="footer" /></section>', props: ['open'] },
  OSpinner: { template: '<span>loading</span>' },
  FileGrid: {
    props: ['files'],
    emits: ['file-contextmenu'],
    template: `
      <div>
        <button
          v-for="file in files"
          :key="file.id"
          :data-testid="'file-menu-' + file.id"
          @click="$emit('file-contextmenu', file, $event)"
        >{{ file.fileName }}</button>
      </div>
    `,
  },
  FileList: true,
  FolderPickerModal: true,
  UploadZone: true,
  FileContextMenu: {
    props: ['visible', 'items'],
    template: `
      <div v-if="visible" data-testid="context-menu">
        <template v-for="(item, index) in items" :key="index">
          <button v-if="!item.divider" type="button" @click="item.action()">{{ item.label }}</button>
        </template>
      </div>
    `,
  },
}

beforeEach(() => {
  setActivePinia(createPinia())
  routerPush.mockReset()
  listTeams.mockResolvedValue({ data: { data: [] } })
  getTeam.mockResolvedValue({
    data: {
      data: {
        id: 10,
        name: 'Product',
        ownerId: 1,
        storageQuota: 1024,
        storageUsed: 0,
        role: 'ADMIN',
        createdAt: '2026-01-01T00:00:00',
        updatedAt: '2026-01-01T00:00:00',
      },
    },
  })
  listTeamFiles.mockResolvedValue({
    data: { data: { records: [file(7, 'design.docx')], total: 1, page: 1, pages: 1 } },
  })
  listTeamFilePermissions.mockResolvedValue({ data: { data: [] } })
  listMembers.mockResolvedValue({
    data: {
      data: [
        { teamId: 10, userId: 1, username: 'owner', email: null, role: 'OWNER', status: 1 },
        { teamId: 10, userId: 2, username: 'chain', email: null, role: 'ADMIN', status: 1 },
      ],
    },
  })
  upsertTeamFilePermission.mockResolvedValue({ data: { data: null } })
  getTeamFileEffectivePermission.mockResolvedValue({
    data: { data: permission({ canWrite: false, canShare: false, canDelete: false, canManage: true }) },
  })
})

describe('team file permissions UI', () => {
  it('builds file actions from the selected file effective permission', async () => {
    const wrapper = mount(TeamFilesPage, { global: { stubs } })
    await flushPromises()
    await wrapper.find('[data-testid="file-menu-7"]').trigger('click')
    await flushPromises()

    expect(getTeamFileEffectivePermission).toHaveBeenCalledWith(10, 7)
    expect(wrapper.find('[data-testid="context-menu"]').text()).toContain('权限')
    expect(wrapper.find('[data-testid="context-menu"]').text()).not.toContain('分享')
    expect(wrapper.find('[data-testid="context-menu"]').text()).not.toContain('标签')
    expect(wrapper.find('[data-testid="context-menu"]').text()).not.toContain('移动到')
    expect(wrapper.find('[data-testid="context-menu"]').text()).not.toContain('删除')
  })

  it('uses team members for user overrides and blocks self-locking changes', async () => {
    const auth = useAuthStore()
    auth.user = {
      id: 2,
      username: 'chain',
      email: 'chain@test.local',
      role: 'ROLE_USER',
      status: 1,
      storageUsed: 0,
      storageQuota: 1024,
      webdavEnabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    }

    const wrapper = mount(TeamFilesPage, { global: { stubs } })
    await flushPromises()
    await wrapper.find('[data-testid="file-menu-7"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="context-menu"] button:last-child').trigger('click')
    await flushPromises()

    const subjectType = wrapper.findAll('select').find(select => select.text().includes('按用户'))
    expect(subjectType).toBeTruthy()
    await subjectType!.setValue('USER')
    await flushPromises()

    expect(wrapper.text()).toContain('chain · ADMIN')
    expect(wrapper.text()).toContain('这会移除你自己的管理权限')

    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(upsertTeamFilePermission).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('这会移除你自己的管理权限，后端会拒绝该操作。')
  })
})
