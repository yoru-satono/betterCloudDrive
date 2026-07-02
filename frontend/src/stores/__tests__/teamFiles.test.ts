import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTeamFilesStore } from '@/stores/teamFiles'
import * as teamsApi from '@/api/teams'
import type { FileEntity } from '@/types/file'
import type { TeamEffectivePermission } from '@/types/team'

vi.mock('@/api/teams', () => ({
  getTeam: vi.fn(),
  getTeamFile: vi.fn(),
  listTeamFiles: vi.fn(),
  getTeamFileEffectivePermission: vi.fn(),
}))

const getTeam = teamsApi.getTeam as Mock
const listTeamFiles = teamsApi.listTeamFiles as Mock
const getTeamFileEffectivePermission = teamsApi.getTeamFileEffectivePermission as Mock

const file = (id: number, fileName: string): FileEntity => ({
  id,
  userId: 1,
  parentId: null,
  fileName,
  fileType: 'file',
  mimeType: 'text/plain',
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
  ...patch,
})

beforeEach(() => {
  setActivePinia(createPinia())
  getTeam.mockResolvedValue({
    data: { data: { id: 10, name: 'Product', ownerId: 1, storageQuota: 1024, storageUsed: 0, role: 'ADMIN' } },
  })
  listTeamFiles.mockResolvedValue({
    data: { data: { records: [file(7, 'design.docx')], total: 1, page: 1, pages: 1 } },
  })
  getTeamFileEffectivePermission.mockReset()
})

describe('team files store', () => {
  it('caches per-file effective permissions and supports forced refresh', async () => {
    const readonly = permission({ canWrite: false, canShare: false, canDelete: false })
    const writable = permission({ canWrite: true, canShare: false, canDelete: false })
    getTeamFileEffectivePermission
      .mockResolvedValueOnce({ data: { data: readonly } })
      .mockResolvedValueOnce({ data: { data: writable } })

    const store = useTeamFilesStore()
    await store.openDirectory(10, null)

    await expect(store.loadFilePermission(10, 7)).resolves.toEqual(readonly)
    expect(store.permissionForFile(7)).toEqual(readonly)
    await expect(store.loadFilePermission(10, 7)).resolves.toEqual(readonly)
    expect(getTeamFileEffectivePermission).toHaveBeenCalledTimes(1)

    await expect(store.loadFilePermission(10, 7, true)).resolves.toEqual(writable)
    expect(store.permissionForFile(7)).toEqual(writable)
    expect(getTeamFileEffectivePermission).toHaveBeenCalledTimes(2)
  })

  it('updates current folder permission when setting the current folder file permission', () => {
    const store = useTeamFilesStore()
    store.currentParentId = 7

    store.setFilePermission(7, permission({ canWrite: false, canShare: false, canDelete: false }))

    expect(store.canWrite).toBe(false)
    expect(store.canShare).toBe(false)
    expect(store.canDelete).toBe(false)
  })
})
