import { beforeEach, describe, expect, it } from 'vitest'
import {
  createResumableScope,
  findResumableDirectory,
  findResumableUpload,
  readResumableDirectories,
  readResumableUploads,
  removeResumableUpload,
  saveResumableDirectory,
  saveResumableUpload,
} from '@/api/resumableUpload'

const scope = createResumableScope('/api/v1', 7)

beforeEach(() => {
  localStorage.clear()
})

describe('resumable upload persistence', () => {
  it('isolates accounts and servers, normalizes relative addresses, and discards legacy keys', () => {
    const record = {sessionId:'a',parentId:null,fileName:'private.txt',displayName:'private.txt',fileSize:8,md5Hash:'a',totalChunks:1,chunkSize:8,createdAt:Date.now(),updatedAt:Date.now()}
    localStorage.setItem('bcd.resumableUploads', JSON.stringify([record]))
    localStorage.setItem('bcd.resumableUploadDirectories', '[]')
    saveResumableUpload(scope,record)
    const otherAccount = createResumableScope('/api/v1',8)
    const otherServer = createResumableScope('https://another.invalid/api/v1',7)
    expect(readResumableUploads(otherAccount)).toEqual([])
    expect(readResumableUploads(otherServer)).toEqual([])
    removeResumableUpload(otherAccount,'a')
    expect(readResumableUploads(createResumableScope(scope.apiBaseUrl+'/',7))).toMatchObject([{sessionId:'a',fileName:'private.txt'}])
    expect(localStorage.getItem('bcd.resumableUploads')).toBeNull()
    expect(localStorage.getItem('bcd.resumableUploadDirectories')).toBeNull()
  })

  it('keeps personal and team directory mappings separate in the same account', () => {
    const record={pathKey:'root:private',parentPathKey:null,baseParentId:null,remoteId:21,remoteName:'private',createdAt:Date.now(),updatedAt:Date.now()}
    saveResumableDirectory(scope,record)
    saveResumableDirectory(scope,{...record,teamId:9,remoteId:31})
    expect(findResumableDirectory(scope,record.pathKey)?.remoteId).toBe(21)
    expect(findResumableDirectory(scope,record.pathKey,9)?.remoteId).toBe(31)
    expect(readResumableDirectories(createResumableScope('/api/v1',8))).toEqual([])
  })
  it('saves, finds, and removes upload sessions by file identity', () => {
    saveResumableUpload(scope, {
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

    expect(findResumableUpload(scope, {
      parentId: 10,
      fileName: 'large.bin',
      displayName: 'large.bin',
      fileSize: 12,
      md5Hash: 'md5-1',
    })).toMatchObject({ sessionId: 'session-1' })

    removeResumableUpload(scope, 'session-1')

    expect(readResumableUploads(scope)).toEqual([])
  })

  it('does not reuse a session for a different parent folder', () => {
    saveResumableUpload(scope, {
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

    expect(findResumableUpload(scope, {
      parentId: 11,
      fileName: 'same.bin',
      displayName: 'same.bin',
      fileSize: 12,
      md5Hash: 'same-md5',
    })).toBeNull()
  })

  it('drops expired upload sessions', () => {
    saveResumableUpload(scope, {
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

    expect(readResumableUploads(scope)).toEqual([])
  })

  it('saves folder directory mappings for resumed folder uploads', () => {
    saveResumableDirectory(scope, {
      pathKey: '10:Photos/2026',
      parentPathKey: '10:Photos',
      baseParentId: 10,
      remoteId: 42,
      remoteName: '2026',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })

    expect(findResumableDirectory(scope, '10:Photos/2026')).toMatchObject({
      remoteId: 42,
      remoteName: '2026',
    })
    expect(readResumableDirectories(scope)).toHaveLength(1)
  })
})
