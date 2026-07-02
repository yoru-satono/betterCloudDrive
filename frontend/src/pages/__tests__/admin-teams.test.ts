import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import AdminTeams from '@/pages/admin/AdminTeams.vue'
import * as adminApi from '@/api/admin'
import { toast } from 'vue-sonner'
import type { AdminTeam } from '@/api/admin'
import type { FileEntity } from '@/types/file'
import type { TeamMember, TeamOperationLog } from '@/types/team'

vi.mock('@/api/admin', () => ({
  listTeams: vi.fn(),
  getTeam: vi.fn(),
  updateTeamQuota: vi.fn(),
  archiveTeam: vi.fn(),
  unarchiveTeam: vi.fn(),
  restoreTeam: vi.fn(),
  purgeTeam: vi.fn(),
  listTeamMembers: vi.fn(),
  listTeamFiles: vi.fn(),
  listTeamAuditLogs: vi.fn(),
  exportTeamAuditLogs: vi.fn(),
}))

const listTeams = adminApi.listTeams as Mock
const getTeam = adminApi.getTeam as Mock
const updateTeamQuota = adminApi.updateTeamQuota as Mock
const archiveTeam = adminApi.archiveTeam as Mock
const unarchiveTeam = adminApi.unarchiveTeam as Mock
const restoreTeam = adminApi.restoreTeam as Mock
const purgeTeam = adminApi.purgeTeam as Mock
const listTeamMembers = adminApi.listTeamMembers as Mock
const listTeamFiles = adminApi.listTeamFiles as Mock
const listTeamAuditLogs = adminApi.listTeamAuditLogs as Mock
const exportTeamAuditLogs = adminApi.exportTeamAuditLogs as Mock

vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

function page<T>(records: T[], total = records.length) {
  return {
    data: {
      data: {
        records,
        total,
        page: 1,
        size: 20,
        pages: Math.max(1, Math.ceil(total / 20)),
      },
    },
  }
}

function team(patch: Partial<AdminTeam> = {}): AdminTeam {
  return {
    id: 10,
    name: 'E2E Admin Team',
    ownerId: 1,
    storageQuota: 10 * 1024 * 1024 * 1024,
    storageUsed: 1024,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    archivedAt: null,
    archivedBy: null,
    deletedAt: null,
    deletedBy: null,
    purgeAfter: null,
    ...patch,
  }
}

function member(): TeamMember {
  return {
    teamId: 10,
    userId: 2,
    username: 'editor',
    email: 'editor@test.local',
    role: 'EDITOR',
    status: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  }
}

function file(): FileEntity {
  return {
    id: 20,
    userId: 1,
    parentId: null,
    fileName: 'team-admin-file.txt',
    fileType: 'file',
    mimeType: 'text/plain',
    fileSize: 12,
    storagePath: null,
    md5Hash: null,
    isDeleted: false,
    versionCount: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-02T00:00:00',
  }
}

function auditLog(): TeamOperationLog {
  return {
    id: 30,
    teamId: 10,
    actorUserId: 1,
    action: 'FILE_DELETE',
    targetType: 'FILE',
    targetId: 20,
    result: 1,
    statusCode: 200,
    requestId: 'req-1',
    traceId: 'trace-1',
    details: 'deleted test file',
    createdAt: '2026-01-03T00:00:00',
  }
}

function mountPage() {
  return mount(AdminTeams, {
    global: {
      stubs: {
        OModal: {
          props: ['open', 'title'],
          template: '<section v-if="open" role="dialog" :aria-label="title"><slot /></section>',
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
  vi.stubGlobal('confirm', vi.fn(() => true))
  listTeams.mockReset().mockResolvedValue(page([team()]))
  getTeam.mockReset().mockResolvedValue({ data: { data: team() } })
  updateTeamQuota.mockReset().mockResolvedValue({ data: { data: team({ storageQuota: 2 * 1024 * 1024 * 1024 }) } })
  archiveTeam.mockReset().mockResolvedValue({ data: { data: team({ archivedAt: '2026-01-04T00:00:00' }) } })
  unarchiveTeam.mockReset().mockResolvedValue({ data: { data: team() } })
  restoreTeam.mockReset().mockResolvedValue({ data: { data: team() } })
  purgeTeam.mockReset().mockResolvedValue({ data: { data: null } })
  listTeamMembers.mockReset().mockResolvedValue({ data: { data: [member()] } })
  listTeamFiles.mockReset().mockResolvedValue(page([file()]))
  listTeamAuditLogs.mockReset().mockResolvedValue(page([auditLog()]))
  exportTeamAuditLogs.mockReset().mockResolvedValue({ data: new Blob(['audit'], { type: 'text/csv' }) })
})

describe('AdminTeams', () => {
  it('filters teams and submits quota/archive actions', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('E2E Admin Team')

    await wrapper.find('input[placeholder="搜索团队..."]').setValue('product')
    await wrapper.find('.teams-toolbar__status').setValue('all')
    await button(wrapper, '搜索').trigger('click')
    expect(listTeams).toHaveBeenLastCalledWith(1, 20, 'product', 'all')

    await wrapper.find('.quota-btn').trigger('click')
    await wrapper.find('.quota-editor input').setValue('abc')
    await button(wrapper, '保存').trigger('click')
    expect(updateTeamQuota).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('请输入有效的团队配额')

    await wrapper.find('.quota-editor input').setValue('2.5')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(updateTeamQuota).toHaveBeenCalledWith(10, 2684354560)
    expect(toast.success).toHaveBeenCalledWith('团队配额已更新')

    await button(wrapper, '归档').trigger('click')
    await flushPromises()
    expect(archiveTeam).toHaveBeenCalledWith(10)
    expect(toast.success).toHaveBeenCalledWith('团队已归档')
  })

  it('restores and purges deleted teams from the admin list', async () => {
    listTeams.mockResolvedValue(page([team({
      id: 11,
      name: 'Deleted Team',
      deletedAt: '2026-01-05T00:00:00',
      deletedBy: 1,
      purgeAfter: '2026-02-05T00:00:00',
    })]))

    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, '恢复').trigger('click')
    await flushPromises()
    expect(restoreTeam).toHaveBeenCalledWith(11)
    expect(toast.success).toHaveBeenCalledWith('团队已恢复')

    await button(wrapper, '清理').trigger('click')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Deleted Team'))
    expect(purgeTeam).toHaveBeenCalledWith(11)
    expect(toast.success).toHaveBeenCalledWith('团队已清理')
  })

  it('opens team details with members, files, audit logs, and CSV export', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await button(wrapper, '详情').trigger('click')
    await flushPromises()

    expect(getTeam).not.toHaveBeenCalled()
    expect(listTeamMembers).toHaveBeenCalledWith(10)
    expect(listTeamFiles).toHaveBeenCalledWith(10, expect.objectContaining({ page: 1, size: 10, deleted: false }))
    expect(listTeamAuditLogs).toHaveBeenCalledWith(10, expect.objectContaining({ page: 1, size: 10 }))
    expect(wrapper.text()).toContain('editor')

    await button(wrapper, '文件').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('team-admin-file.txt')

    await button(wrapper, '审计').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('FILE_DELETE')

    await button(wrapper, '导出 CSV').trigger('click')
    await flushPromises()

    expect(exportTeamAuditLogs).toHaveBeenCalledWith(10, {
      action: undefined,
      targetType: undefined,
      result: undefined,
    })
    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('审计日志已导出')
  })
})
