const STORAGE_KEY = 'bcd.resumableUploads'
const DIRECTORY_STORAGE_KEY = 'bcd.resumableUploadDirectories'
const SESSION_TTL_MS = 24 * 60 * 60 * 1000

export interface ResumableUploadRecord {
  sessionId: string
  parentId: number | null
  fileName: string
  displayName: string
  fileSize: number
  md5Hash: string
  totalChunks: number
  chunkSize: number
  createdAt: number
  updatedAt: number
}

export interface UploadRecordIdentity {
  parentId: number | null
  fileName: string
  displayName: string
  fileSize: number
  md5Hash: string
}

export interface ResumableDirectoryRecord {
  pathKey: string
  parentPathKey: string | null
  baseParentId: number | null
  remoteId: number
  remoteName: string
  createdAt: number
  updatedAt: number
}

export function findResumableUpload(identity: UploadRecordIdentity) {
  const records = readResumableUploads()
  return records.find(record => isSameUpload(record, identity)) || null
}

export function saveResumableUpload(record: ResumableUploadRecord) {
  const records = readResumableUploads().filter(existing =>
    existing.sessionId !== record.sessionId
    && !isSameUpload(existing, record),
  )
  records.push({ ...record, updatedAt: Date.now() })
  writeResumableUploads(records)
}

export function removeResumableUpload(sessionId: string | undefined) {
  if (!sessionId) return
  writeResumableUploads(readResumableUploads().filter(record => record.sessionId !== sessionId))
}

export function readResumableUploads() {
  const now = Date.now()
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]') as ResumableUploadRecord[]
    const records = Array.isArray(parsed) ? parsed.filter(isValidRecord) : []
    const fresh = records.filter(record => now - record.createdAt < SESSION_TTL_MS)
    if (fresh.length !== records.length) writeResumableUploads(fresh)
    return fresh
  } catch {
    return []
  }
}

export function findResumableDirectory(pathKey: string) {
  return readResumableDirectories().find(record => record.pathKey === pathKey) || null
}

export function saveResumableDirectory(record: ResumableDirectoryRecord) {
  const records = readResumableDirectories().filter(existing => existing.pathKey !== record.pathKey)
  records.push({ ...record, updatedAt: Date.now() })
  writeResumableDirectories(records)
}

export function readResumableDirectories() {
  const now = Date.now()
  try {
    const parsed = JSON.parse(localStorage.getItem(DIRECTORY_STORAGE_KEY) || '[]') as ResumableDirectoryRecord[]
    const records = Array.isArray(parsed) ? parsed.filter(isValidDirectoryRecord) : []
    const fresh = records.filter(record => now - record.createdAt < SESSION_TTL_MS)
    if (fresh.length !== records.length) writeResumableDirectories(fresh)
    return fresh
  } catch {
    return []
  }
}

function writeResumableUploads(records: ResumableUploadRecord[]) {
  try {
    if (records.length) localStorage.setItem(STORAGE_KEY, JSON.stringify(records))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore unavailable storage
  }
}

function writeResumableDirectories(records: ResumableDirectoryRecord[]) {
  try {
    if (records.length) localStorage.setItem(DIRECTORY_STORAGE_KEY, JSON.stringify(records))
    else localStorage.removeItem(DIRECTORY_STORAGE_KEY)
  } catch {
    // ignore unavailable storage
  }
}

function isSameUpload(record: UploadRecordIdentity, identity: UploadRecordIdentity) {
  return record.parentId === identity.parentId
    && record.fileName === identity.fileName
    && record.displayName === identity.displayName
    && record.fileSize === identity.fileSize
    && record.md5Hash === identity.md5Hash
}

function isValidRecord(record: ResumableUploadRecord) {
  return !!record
    && typeof record.sessionId === 'string'
    && (record.parentId === null || typeof record.parentId === 'number')
    && typeof record.fileName === 'string'
    && typeof record.displayName === 'string'
    && typeof record.fileSize === 'number'
    && typeof record.md5Hash === 'string'
    && typeof record.totalChunks === 'number'
    && typeof record.chunkSize === 'number'
    && typeof record.createdAt === 'number'
    && typeof record.updatedAt === 'number'
}

function isValidDirectoryRecord(record: ResumableDirectoryRecord) {
  return !!record
    && typeof record.pathKey === 'string'
    && (record.parentPathKey === null || typeof record.parentPathKey === 'string')
    && (record.baseParentId === null || typeof record.baseParentId === 'number')
    && typeof record.remoteId === 'number'
    && typeof record.remoteName === 'string'
    && typeof record.createdAt === 'number'
    && typeof record.updatedAt === 'number'
}
