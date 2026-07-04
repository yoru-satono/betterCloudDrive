import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import api from '@/api/client'
import * as uploadApi from '@/api/upload'
import * as recycleApi from '@/api/recycle'
import * as favoritesApi from '@/api/favorites'
import * as tagsApi from '@/api/tags'
import * as sharesApi from '@/api/shares'
import * as adminApi from '@/api/admin'
import * as filesApi from '@/api/files'
import * as authApi from '@/api/auth'
import * as teamsApi from '@/api/teams'
import * as officeApi from '@/api/office'
import * as versionsApi from '@/api/versions'
import * as notificationsApi from '@/api/notifications'
import { downloadFolderZip, downloadSharedFile, downloadSharedFolderZip, previewFile } from '@/api/download'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
    put: vi.fn(),
  },
}))

const apiMock = api as unknown as Record<'get' | 'post' | 'delete' | 'patch' | 'put', Mock>

describe('API endpoint contracts', () => {
  beforeEach(() => {
    Object.values(apiMock).forEach(mock => mock.mockResolvedValue({ data: { code: 200, data: null } }))
  })

  it('uses registration code and verified registration routes', async () => {
    await authApi.sendRegistrationCode('user@test.local')
    expect(apiMock.post).toHaveBeenCalledWith('/auth/register-code/send', { email: 'user@test.local' })

    await authApi.register('user', 'TestPass123!', 'user@test.local', '123456')
    expect(apiMock.post).toHaveBeenCalledWith('/auth/register', {
      username: 'user',
      password: 'TestPass123!',
      email: 'user@test.local',
      verificationCode: '123456',
    })
  })

  it('uses backend upload routes and md5Hash field', async () => {
    await uploadApi.instantUpload(10, 'a.txt', 12, 'hash')
    expect(apiMock.post).toHaveBeenCalledWith(
      '/upload/instant',
      { parentId: 10, fileName: 'a.txt', fileSize: 12, md5Hash: 'hash' },
      { suppressToast: true },
    )

    await uploadApi.initMultipart(10, 'a.txt', 12, 'hash', 2)
    expect(apiMock.post).toHaveBeenCalledWith(
      '/upload/init',
      { parentId: 10, fileName: 'a.txt', fileSize: 12, md5Hash: 'hash', totalChunks: 2 },
      { suppressToast: true },
    )

    await uploadApi.initMultipart(null, 'empty.txt', 0, 'd41d8cd98f00b204e9800998ecf8427e', 0)
    expect(apiMock.post).toHaveBeenCalledWith(
      '/upload/init',
      { parentId: null, fileName: 'empty.txt', fileSize: 0, md5Hash: 'd41d8cd98f00b204e9800998ecf8427e', totalChunks: 0 },
      { suppressToast: true },
    )

    await uploadApi.uploadChunk('session-1', 1, new Blob(['x']))
    expect(apiMock.post).toHaveBeenCalledWith(
      '/upload/session-1/chunk',
      expect.any(FormData),
      expect.objectContaining({ params: { chunkNumber: 1 }, suppressToast: true }),
    )

    await uploadApi.completeMultipart('session-1')
    expect(apiMock.post).toHaveBeenCalledWith('/upload/session-1/complete', undefined, { suppressToast: true })

    await uploadApi.abortMultipart('session-1')
    expect(apiMock.post).toHaveBeenCalledWith('/upload/session-1/cancel', undefined, { suppressToast: true })

    await uploadApi.getUploadStatus('session-1')
    expect(apiMock.get).toHaveBeenCalledWith('/upload/session-1/status', { suppressToast: true })
  })

  it('uses team management and team file routes', async () => {
    await teamsApi.listTeams()
    expect(apiMock.get).toHaveBeenCalledWith('/teams')

    await teamsApi.createTeam('Product')
    expect(apiMock.post).toHaveBeenCalledWith('/teams', { name: 'Product' })

    await teamsApi.getTeam(10)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10')

    await teamsApi.updateTeam(10, 'Product Docs')
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10', { name: 'Product Docs' })

    await teamsApi.deleteTeam(10)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10')

    await teamsApi.leaveTeam(10)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/leave')

    await teamsApi.transferTeamOwner(10, 5)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/owner', { userId: 5 })

    await teamsApi.listMembers(10)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/members')

    await teamsApi.addMember(10, 'chain', 'EDITOR')
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/members', { username: 'chain', role: 'EDITOR' })

    await teamsApi.updateMember(10, 5, 'ADMIN')
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/members/5', { role: 'ADMIN' })

    await teamsApi.removeMember(10, 5)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/members/5')

    await teamsApi.createInvite(10, 'member@test.local', 'VIEWER')
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/invites', { email: 'member@test.local', role: 'VIEWER' })

    await teamsApi.listInvites(10)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/invites')

    await teamsApi.resendInvite(10, 4)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/invites/4/resend')

    await teamsApi.cancelInvite(10, 4)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/invites/4')

    await teamsApi.getInvite('invite-token')
    expect(apiMock.get).toHaveBeenCalledWith('/team-invites/invite-token')

    await teamsApi.acceptInvite('invite-token')
    expect(apiMock.post).toHaveBeenCalledWith('/team-invites/invite-token/accept')

    await teamsApi.rejectInvite('invite-token')
    expect(apiMock.post).toHaveBeenCalledWith('/team-invites/invite-token/reject')

    await teamsApi.listTeamFiles(10, { parentId: 5, page: 1, size: 50 })
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files', {
      params: { parentId: 5, page: 1, size: 50 },
    })

    await teamsApi.searchTeamFiles(10, 'contract', 2, 30)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files/search', {
      params: { q: 'contract', page: 2, size: 30 },
    })

    await teamsApi.getTeamFile(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files/7')

    await teamsApi.getTeamFileEffectivePermission(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files/7/effective-permission')

    await teamsApi.listTeamFilePermissions(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files/7/permissions')

    await teamsApi.upsertTeamFilePermission(10, 7, {
      subjectType: 'ROLE',
      subjectRole: 'VIEWER',
      canRead: true,
      canWrite: false,
      canShare: false,
      canDelete: false,
      canManage: false,
    })
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/files/7/permissions', {
      subjectType: 'ROLE',
      subjectRole: 'VIEWER',
      canRead: true,
      canWrite: false,
      canShare: false,
      canDelete: false,
      canManage: false,
    })

    await teamsApi.deleteTeamFilePermission(10, 7, 3)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/files/7/permissions/3')

    await teamsApi.createTeamFolder(10, null, 'Docs')
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/files/folder', { parentId: null, folderName: 'Docs' })

    await teamsApi.renameTeamFile(10, 7, 'New.txt')
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/files/7', { newName: 'New.txt' })

    await teamsApi.moveTeamFile(10, 7, null)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/files/7/move', { targetParentId: null })

    await teamsApi.deleteTeamFiles(10, [7])
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/files', { data: { fileIds: [7] } })

    await teamsApi.listTeamRecycleBin(10, 2, 30, 'old')
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/recycle-bin', {
      params: { page: 2, size: 30, q: 'old' },
    })

    await teamsApi.restoreTeamFiles(10, [7, 8])
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/recycle-bin/7/restore')
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/recycle-bin/8/restore')

    await teamsApi.permanentDeleteTeamFiles(10, [7, 8])
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/recycle-bin/7')
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/recycle-bin/8')

    await teamsApi.emptyTeamRecycleBin(10)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/recycle-bin')

    await teamsApi.createTeamShare(10, { fileId: 7, maxVisits: 3 })
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/shares', { fileId: 7, maxVisits: 3 })

    await teamsApi.listTeamShares(10, 1, 20, 'share')
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/shares', {
      params: { page: 1, size: 20, q: 'share' },
    })

    await teamsApi.getTeamShare(10, 9)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/shares/9')

    await teamsApi.getTeamSharePassword(10, 9)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/shares/9/password')

    await teamsApi.updateTeamShare(10, 9, { maxVisits: 5, expireAt: 0 })
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/shares/9', { maxVisits: 5, expireAt: 0 })

    await teamsApi.updateTeamShare(10, 9, { clearPassword: true, clearMaxVisits: true, clearExpireAt: true })
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/shares/9', {
      clearPassword: true,
      clearMaxVisits: true,
      clearExpireAt: true,
    })

    await teamsApi.deleteTeamShare(10, 9)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/shares/9')

    await teamsApi.listTeamAuditLogs(10, {
      actorUserId: 5,
      action: 'INVITE_ACCEPT',
      targetType: 'USER',
      result: 1,
      startAt: '2026-06-27T12:00:00',
      endAt: '2026-06-27T13:00:00',
      page: 2,
      size: 30,
    })
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/audit-logs', {
      params: {
        actorUserId: 5,
        action: 'INVITE_ACCEPT',
        targetType: 'USER',
        result: 1,
        startAt: '2026-06-27T12:00:00',
        endAt: '2026-06-27T13:00:00',
        page: 2,
        size: 30,
      },
    })

    await teamsApi.exportTeamAuditLogs(10, { action: 'FILE_DELETE' })
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/audit-logs/export', {
      params: { action: 'FILE_DELETE' },
      responseType: 'blob',
    })

    await teamsApi.addTeamFavorite(10, 7)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/favorites/7')

    await teamsApi.removeTeamFavorite(10, 7)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/favorites/7')

    await teamsApi.isTeamFavorite(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/favorites/7/status')

    await teamsApi.listTeamFavorites(10, 2, 30, 'fav')
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/favorites', {
      params: { page: 2, size: 30, q: 'fav' },
    })

    await teamsApi.createTeamTag(10, '合同', '#00d4aa')
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/tags', { tagName: '合同', color: '#00d4aa' })

    await teamsApi.listTeamTags(10, '合')
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/tags', { params: { q: '合' } })

    await teamsApi.updateTeamTag(10, 3, '合同归档', '#00d4aa')
    expect(apiMock.put).toHaveBeenCalledWith('/teams/10/tags/3', { tagName: '合同归档', color: '#00d4aa' })

    await teamsApi.deleteTeamTag(10, 3)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/tags/3')

    await teamsApi.addTeamFileTag(10, 7, 3)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/tags/3/files', { fileIds: [7] })

    await teamsApi.removeTeamFileTag(10, 7, 3)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/tags/3/files/7')

    await teamsApi.listTeamFilesByTag(10, 3, 2, 30, 'doc')
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/tags/3/files', {
      params: { page: 2, size: 30, q: 'doc' },
    })

    await versionsApi.listTeamVersions(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/files/7/versions')

    await versionsApi.deleteTeamVersion(10, 7, 2)
    expect(apiMock.delete).toHaveBeenCalledWith('/teams/10/files/7/versions/2')

    await versionsApi.restoreTeamVersion(10, 7, 2)
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/files/7/versions/2/restore')
  })

  it('uses OnlyOffice config routes', async () => {
    await officeApi.getOfficeConfig(7)
    expect(apiMock.get).toHaveBeenCalledWith('/office/files/7/config')

    await officeApi.getTeamOfficeConfig(10, 7)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/office/files/7/config')
  })

  it('uses notification routes', async () => {
    await notificationsApi.listNotifications(2, 30, true)
    expect(apiMock.get).toHaveBeenCalledWith('/notifications', {
      params: { page: 2, size: 30, unreadOnly: true },
    })

    await notificationsApi.listNotifications(1, 20, false, 'TEAM_QUOTA_ALERT')
    expect(apiMock.get).toHaveBeenCalledWith('/notifications', {
      params: { page: 1, size: 20, unreadOnly: false, type: 'TEAM_QUOTA_ALERT' },
    })

    await notificationsApi.getUnreadCount()
    expect(apiMock.get).toHaveBeenCalledWith('/notifications/unread-count')

    await notificationsApi.markNotificationRead(7)
    expect(apiMock.post).toHaveBeenCalledWith('/notifications/7/read')

    await notificationsApi.markAllNotificationsRead()
    expect(apiMock.post).toHaveBeenCalledWith('/notifications/read-all')
  })

  it('uses team upload and preview routes', async () => {
    await uploadApi.initMultipart(null, 'team.txt', 12, 'hash', 1, undefined, { teamId: 10 })
    expect(apiMock.post).toHaveBeenCalledWith(
      '/teams/10/upload/init',
      { parentId: null, fileName: 'team.txt', fileSize: 12, md5Hash: 'hash', totalChunks: 1 },
      { suppressToast: true },
    )

    await uploadApi.uploadChunk('team-session', 0, new Blob(['x']), undefined, { teamId: 10 })
    expect(apiMock.post).toHaveBeenCalledWith(
      '/teams/10/upload/team-session/chunk',
      expect.any(FormData),
      expect.objectContaining({ params: { chunkNumber: 0 }, suppressToast: true }),
    )

    await uploadApi.completeMultipart('team-session', { teamId: 10 })
    expect(apiMock.post).toHaveBeenCalledWith('/teams/10/upload/team-session/complete', undefined, { suppressToast: true })

    await previewFile(7, 10)
    expect(apiMock.get).toHaveBeenCalledWith('/teams/10/preview/7', { responseType: 'blob', suppressToast: true })
  })

  it('uses file detail, move, copy, and preview routes', async () => {
    await filesApi.getFile(5)
    expect(apiMock.get).toHaveBeenCalledWith('/files/5')

    await filesApi.moveFile(5, 2)
    expect(apiMock.post).toHaveBeenCalledWith('/files/5/move', { targetParentId: 2 })

    await filesApi.copyFile(5, null)
    expect(apiMock.post).toHaveBeenCalledWith('/files/5/copy', { targetParentId: null })

    await previewFile(5)
    expect(apiMock.get).toHaveBeenCalledWith('/preview/5', { responseType: 'blob', suppressToast: true })

    await downloadFolderZip(5, 'docs')
    expect(apiMock.post).toHaveBeenCalledWith('/download/folders/5/zip/ticket', undefined, { suppressToast: true })
  })

  it('maps recycle bin batch actions to per-file backend calls', async () => {
    await recycleApi.listRecycleBin(1, 20, 'deleted')
    expect(apiMock.get).toHaveBeenCalledWith('/recycle-bin', {
      params: { page: 1, size: 20, q: 'deleted' },
    })

    await recycleApi.restoreFiles([1, 2])
    expect(apiMock.post).toHaveBeenCalledWith('/recycle-bin/1/restore')
    expect(apiMock.post).toHaveBeenCalledWith('/recycle-bin/2/restore')

    await recycleApi.permanentDelete([3, 4])
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin/3')
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin/4')

    await recycleApi.emptyRecycleBin()
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin')

    await versionsApi.restoreVersion(7, 2)
    expect(apiMock.post).toHaveBeenCalledWith('/files/7/versions/2/restore')
  })

  it('uses backend favorite and tag file association routes', async () => {
    await favoritesApi.listFavorites(1, 20, 'report')
    expect(apiMock.get).toHaveBeenCalledWith('/favorites', {
      params: { page: 1, size: 20, q: 'report' },
    })

    await favoritesApi.addFavorite(9)
    expect(apiMock.post).toHaveBeenCalledWith('/favorites/9')

    await tagsApi.listTags('work')
    expect(apiMock.get).toHaveBeenCalledWith('/tags', { params: { q: 'work' } })

    await tagsApi.listFilesByTag(7, 1, 20, 'report')
    expect(apiMock.get).toHaveBeenCalledWith('/tags/7/files', {
      params: { page: 1, size: 20, q: 'report' },
    })

    await tagsApi.addFileTag(9, 7)
    expect(apiMock.post).toHaveBeenCalledWith('/tags/7/files', { fileIds: [9] })

    await tagsApi.removeFileTag(9, 7)
    expect(apiMock.delete).toHaveBeenCalledWith('/tags/7/files/9')

    await tagsApi.updateTag(7, 'work', '#00d4aa')
    expect(apiMock.put).toHaveBeenCalledWith('/tags/7', { tagName: 'work', color: '#00d4aa' })
  })

  it('uses public share access and shared file list routes', async () => {
    await sharesApi.listShares(1, 20, 'abc123')
    expect(apiMock.get).toHaveBeenCalledWith('/shares', {
      params: { page: 1, size: 20, q: 'abc123' },
    })

    await sharesApi.createShare({ fileId: 6, maxVisits: 12 })
    expect(apiMock.post).toHaveBeenCalledWith('/shares', { fileId: 6, maxVisits: 12 })

    await sharesApi.createShare({ fileId: 6, password: 'abcd' })
    expect(apiMock.post).toHaveBeenCalledWith('/shares', { fileId: 6, password: 'abcd' })

    await sharesApi.getShare(9)
    expect(apiMock.get).toHaveBeenCalledWith('/shares/9')

    await sharesApi.getSharePassword(9)
    expect(apiMock.get).toHaveBeenCalledWith('/shares/9/password')

    await sharesApi.updateShare(9, { maxVisits: 5, expireAt: 0 })
    expect(apiMock.put).toHaveBeenCalledWith('/shares/9', { maxVisits: 5, expireAt: 0 })

    await sharesApi.updateShare(9, { clearPassword: true, clearMaxVisits: true, clearExpireAt: true })
    expect(apiMock.put).toHaveBeenCalledWith('/shares/9', {
      clearPassword: true,
      clearMaxVisits: true,
      clearExpireAt: true,
    })

    await sharesApi.accessShare('abc123', 'pw')
    expect(apiMock.post).toHaveBeenCalledWith('/shares/access/abc123', { password: 'pw' }, { suppressToast: true })

    await sharesApi.accessShare('abc123')
    expect(apiMock.post).toHaveBeenCalledWith('/shares/access/abc123', undefined, { suppressToast: true })

    await sharesApi.listSharedFiles('abc123', 5, 2, 50, 'pw')
    expect(apiMock.get).toHaveBeenCalledWith('/shares/access/abc123/files', {
      params: { parentId: 5, page: 2, size: 50, password: 'pw' },
      suppressToast: true,
    })

    await sharesApi.saveSharedItem('abc123', { fileId: 8, targetParentId: 3, password: 'pw' })
    expect(apiMock.post).toHaveBeenCalledWith(
      '/shares/access/abc123/save',
      { fileId: 8, targetParentId: 3, password: 'pw' },
      { suppressToast: true },
    )

    await downloadSharedFile('abc123', 7, 'a.txt', 'pw')
    expect(apiMock.post).toHaveBeenCalledWith(
      '/shares/access/abc123/download/7',
      { password: 'pw' },
      { responseType: 'blob', suppressToast: true },
    )

    await downloadSharedFolderZip('abc123', 8, 'docs', 'pw')
    expect(apiMock.post).toHaveBeenCalledWith(
      '/shares/access/abc123/download/8/zip',
      { password: 'pw' },
      { responseType: 'blob', suppressToast: true },
    )
  })

  it('uses admin keyword search and patch update routes', async () => {
    await adminApi.getStats()
    expect(apiMock.get).toHaveBeenCalledWith('/admin/stats')

    await adminApi.listUsers(1, 100, 'alice', 1)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/users', {
      params: { page: 1, size: 100, keyword: 'alice', status: 1 },
    })

    await adminApi.updateUserStatus(5, 0)
    expect(apiMock.patch).toHaveBeenCalledWith('/admin/users/5/status', { status: 0 })

    await adminApi.updateUserQuota(5, 1024)
    expect(apiMock.patch).toHaveBeenCalledWith('/admin/users/5/quota', { storageQuota: 1024 })

    await adminApi.listTeams(1, 20, 'product')
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams', {
      params: { page: 1, size: 20, keyword: 'product', status: 'active' },
    })

    await adminApi.getTeam(10)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams/10')

    await adminApi.updateTeamQuota(10, 2048)
    expect(apiMock.patch).toHaveBeenCalledWith('/admin/teams/10/quota', { storageQuota: 2048 })

    await adminApi.archiveTeam(10)
    expect(apiMock.post).toHaveBeenCalledWith('/admin/teams/10/archive')

    await adminApi.unarchiveTeam(10)
    expect(apiMock.post).toHaveBeenCalledWith('/admin/teams/10/unarchive')

    await adminApi.restoreTeam(10)
    expect(apiMock.post).toHaveBeenCalledWith('/admin/teams/10/restore')

    await adminApi.purgeTeam(10)
    expect(apiMock.delete).toHaveBeenCalledWith('/admin/teams/10/purge')

    await adminApi.listTeamMembers(10)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams/10/members')

    await adminApi.listTeamFiles(10, { parentId: null, deleted: false, keyword: 'doc', page: 1, size: 10 })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams/10/files', {
      params: { parentId: undefined, deleted: false, keyword: 'doc', page: 1, size: 10 },
    })

    await adminApi.listTeamAuditLogs(10, {
      actorUserId: 5,
      action: 'QUOTA_UPDATE',
      targetType: 'TEAM',
      result: 1,
      startAt: '2026-06-27T12:00:00',
      endAt: '2026-06-27T13:00:00',
      page: 1,
      size: 10,
    })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams/10/audit-logs', {
      params: {
        actorUserId: 5,
        action: 'QUOTA_UPDATE',
        targetType: 'TEAM',
        result: 1,
        startAt: '2026-06-27T12:00:00',
        endAt: '2026-06-27T13:00:00',
        page: 1,
        size: 10,
      },
    })

    await adminApi.exportTeamAuditLogs(10, { actorUserId: 5, action: 'QUOTA_UPDATE', targetType: 'TEAM', result: 1 })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/teams/10/audit-logs/export', {
      params: { actorUserId: 5, action: 'QUOTA_UPDATE', targetType: 'TEAM', result: 1 },
      responseType: 'blob',
    })

    await adminApi.listUserFiles(5, { parentId: 3, page: 1, size: 50 })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/users/5/files', {
      params: { parentId: 3, page: 1, size: 50 },
    })

    await adminApi.getFile(12)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/files/12')

    await adminApi.deleteFile(12)
    expect(apiMock.delete).toHaveBeenCalledWith('/admin/files/12')

    await adminApi.listLogs({
      userId: 5,
      actionType: 'LOGIN',
      requestId: 'req-1',
      traceId: 'trace-1',
      statusCode: 401,
      result: 0,
      startDate: '2026-06-27T12:00:00',
      endDate: '2026-06-27T13:00:00',
      page: 2,
      size: 50,
    })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/logs', {
      params: {
        userId: 5,
        actionType: 'LOGIN',
        requestId: 'req-1',
        traceId: 'trace-1',
        statusCode: 401,
        result: 0,
        startDate: '2026-06-27T12:00:00',
        endDate: '2026-06-27T13:00:00',
        page: 2,
        size: 50,
      },
    })

    await adminApi.listSystemLogs({
      traceId: 'trace-1',
      requestId: 'req-1',
      level: 'WARN',
      logType: 'system',
      keyword: 'quota',
      startTime: '2026-06-27T12:00:00',
      endTime: '2026-06-27T13:00:00',
      limit: 100,
    })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/system-logs', {
      params: {
        traceId: 'trace-1',
        requestId: 'req-1',
        level: 'WARN',
        logType: 'system',
        keyword: 'quota',
        startTime: '2026-06-27T12:00:00',
        endTime: '2026-06-27T13:00:00',
        limit: 100,
      },
    })

    await adminApi.createGrafanaSession()
    expect(apiMock.post).toHaveBeenCalledWith('/admin/grafana/session')
  })
})
