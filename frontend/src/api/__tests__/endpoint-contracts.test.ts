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
    await recycleApi.restoreFiles([1, 2])
    expect(apiMock.post).toHaveBeenCalledWith('/recycle-bin/1/restore')
    expect(apiMock.post).toHaveBeenCalledWith('/recycle-bin/2/restore')

    await recycleApi.permanentDelete([3, 4])
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin/3')
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin/4')

    await recycleApi.emptyRecycleBin()
    expect(apiMock.delete).toHaveBeenCalledWith('/recycle-bin')
  })

  it('uses backend favorite and tag file association routes', async () => {
    await favoritesApi.addFavorite(9)
    expect(apiMock.post).toHaveBeenCalledWith('/favorites/9')

    await tagsApi.addFileTag(9, 7)
    expect(apiMock.post).toHaveBeenCalledWith('/tags/7/files', { fileIds: [9] })

    await tagsApi.removeFileTag(9, 7)
    expect(apiMock.delete).toHaveBeenCalledWith('/tags/7/files/9')

    await tagsApi.updateTag(7, 'work', '#00d4aa')
    expect(apiMock.put).toHaveBeenCalledWith('/tags/7', { tagName: 'work', color: '#00d4aa' })
  })

  it('uses public share access and shared file list routes', async () => {
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

    await sharesApi.accessShare('abc123', 'pw')
    expect(apiMock.post).toHaveBeenCalledWith('/shares/access/abc123', { password: 'pw' }, { suppressToast: true })

    await sharesApi.accessShare('abc123')
    expect(apiMock.post).toHaveBeenCalledWith('/shares/access/abc123', undefined, { suppressToast: true })

    await sharesApi.listSharedFiles('abc123', 5, 2, 50)
    expect(apiMock.get).toHaveBeenCalledWith('/shares/access/abc123/files', {
      params: { parentId: 5, page: 2, size: 50 },
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
    await adminApi.listUsers(1, 100, 'alice', 1)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/users', {
      params: { page: 1, size: 100, keyword: 'alice', status: 1 },
    })

    await adminApi.updateUserStatus(5, 0)
    expect(apiMock.patch).toHaveBeenCalledWith('/admin/users/5/status', { status: 0 })

    await adminApi.updateUserQuota(5, 1024)
    expect(apiMock.patch).toHaveBeenCalledWith('/admin/users/5/quota', { storageQuota: 1024 })

    await adminApi.listUserFiles(5, { parentId: 3, page: 1, size: 50 })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/users/5/files', {
      params: { parentId: 3, page: 1, size: 50 },
    })

    await adminApi.getFile(12)
    expect(apiMock.get).toHaveBeenCalledWith('/admin/files/12')

    await adminApi.deleteFile(12)
    expect(apiMock.delete).toHaveBeenCalledWith('/admin/files/12')
  })
})
