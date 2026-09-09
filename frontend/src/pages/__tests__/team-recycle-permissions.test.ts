import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TeamRecycleBinPage from '@/pages/TeamRecycleBinPage.vue'
import { i18n } from '@/i18n'
import * as teamsApi from '@/api/teams'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { teamId: '10' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/teams', () => ({
  getTeam: vi.fn(),
  listTeamRecycleBin: vi.fn(),
  getTeamFileEffectivePermission: vi.fn(),
  restoreTeamFiles: vi.fn(),
  permanentDeleteTeamFiles: vi.fn(),
  emptyTeamRecycleBin: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => ({ confirm: vi.fn(async () => true) }) }))

const getTeam = teamsApi.getTeam as Mock
const listRecycle = teamsApi.listTeamRecycleBin as Mock
const effective = teamsApi.getTeamFileEffectivePermission as Mock
const restore = teamsApi.restoreTeamFiles as Mock

const stubs = {
  OButton: { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  OEmptyState: true,
  OSpinner: true,
  FileIcon: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  getTeam.mockResolvedValue({ data: { data: { id: 10, name: 'Team', role: 'VIEWER' } } })
  listRecycle.mockResolvedValue({ data: { data: {
    records: [{
      id: 7, parentId: null, fileName: 'deleted.txt', fileType: 'file', mimeType: 'text/plain',
      fileSize: 4, md5Hash: null, versionCount: 1, createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-02T00:00:00', deletedAt: '2026-01-03T00:00:00',
    }],
    total: 1, page: 1, size: 100, pages: 1,
  } } })
  effective.mockResolvedValue({ data: { data: {
    canRead: true, canWrite: true, canShare: false, canDelete: false, canManage: false,
  } } })
  restore.mockResolvedValue([])
})

describe('TeamRecycleBinPage permissions', () => {
  it('uses effective file permissions instead of the base team role', async () => {
    const wrapper = mount(TeamRecycleBinPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('.team-bin__row').trigger('click')
    await flushPromises()

    expect(effective).toHaveBeenCalledWith(10, 7)
    expect(wrapper.text()).toContain(i18n.global.t('team.recyclebin.recover'))
    expect(wrapper.text()).not.toContain(i18n.global.t('team.recyclebin.deletePermanentlyCountPrefix'))
    expect(wrapper.text()).not.toContain(i18n.global.t('team.recyclebin.emptyRecycleBin'))

    const restoreButton = wrapper.findAll('.team-bin__actions button')
      .find(button => button.text().includes(i18n.global.t('team.recyclebin.recover')))
    expect(restoreButton).toBeDefined()
    await restoreButton!.trigger('click')
    await flushPromises()
    expect(restore).toHaveBeenCalledWith(10, [7])
  })
})
