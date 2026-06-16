import { beforeEach, describe, expect, it } from 'vitest'
import {
  findResumableDirectory,
  findResumableUpload,
  readResumableDirectories,
  readResumableUploads,
  removeResumableUpload,
  saveResumableDirectory,
  saveResumableUpload,
} from '@/api/resumableUpload'

beforeEach(() => {
  localStorage.clear()
})

describe('resumable upload persistence', () => {
  it('saves, finds, and removes upload sessions by file identity', () => {
    saveResumableUpload({
      sessionId: 'session-1',
      parentId: 10,
      fileName: 'large.bin',
      displayName: 'large.bin',
      fileSize: 12,
      md5Hash: 'md5-1',
      totalChunks: 2,
      chunkSize: 5,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })

    expect(findResumableUpload({
      parentId: 10,
      fileName: 'large.bin',
      displayName: 'large.bin',
      fileSize: 12,
      md5Hash: 'md5-1',
    })).toMatchObject({ sessionId: 'session-1' })

    removeResumableUpload('session-1')

    expect(readResumableUploads()).toEqual([])
  })

  it('does not reuse a session for a different parent folder', () => {
    saveResumableUpload({
      sessionId: 'session-folder-a',
      parentId: 10,
      fileName: 'same.bin',
      displayName: 'same.bin',
      fileSize: 12,
      md5Hash: 'same-md5',
      totalChunks: 2,
      chunkSize: 5,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })

    expect(findResumableUpload({
      parentId: 11,
      fileName: 'same.bin',
      displayName: 'same.bin',
      fileSize: 12,
      md5Hash: 'same-md5',
    })).toBeNull()
  })

  it('drops expired upload sessions', () => {
    saveResumableUpload({
      sessionId: 'expired-session',
      parentId: null,
      fileName: 'old.bin',
      displayName: 'old.bin',
      fileSize: 1,
      md5Hash: 'md5-old',
      totalChunks: 1,
      chunkSize: 5,
      createdAt: Date.now() - 25 * 60 * 60 * 1000,
      updatedAt: Date.now() - 25 * 60 * 60 * 1000,
    })

    expect(readResumableUploads()).toEqual([])
  })

  it('saves folder directory mappings for resumed folder uploads', () => {
    saveResumableDirectory({
      pathKey: '10:Photos/2026',
      parentPathKey: '10:Photos',
      baseParentId: 10,
      remoteId: 42,
      remoteName: '2026',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })

    expect(findResumableDirectory('10:Photos/2026')).toMatchObject({
      remoteId: 42,
      remoteName: '2026',
    })
    expect(readResumableDirectories()).toHaveLength(1)
  })
})
