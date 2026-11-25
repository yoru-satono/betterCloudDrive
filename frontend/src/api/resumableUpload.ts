const STORAGE_KEY = 'bcd.resumableUploads'
const DIRECTORY_STORAGE_KEY = 'bcd.resumableUploadDirectories'
const SESSION_TTL_MS = 24 * 60 * 60 * 1000

export interface ResumableScope {
  readonly apiBaseUrl: string
  readonly userId: number
}

export function createResumableScope(apiBaseUrl: string, userId: number): ResumableScope {
  const url = new URL(apiBaseUrl, window.location.origin)
  if (!Number.isSafeInteger(userId) || userId <= 0 || !['http:', 'https:'].includes(url.protocol)
    || url.username || url.password || url.search || url.hash) throw new Error('INVALID_UPLOAD_IDENTITY')
  return Object.freeze({ apiBaseUrl: url.href.replace(/\/+$/, ''), userId })
}

// Old records have no trustworthy owner. Never attribute them to the next login.
export function clearLegacyResumableRecords() {
  try {
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem(DIRECTORY_STORAGE_KEY)
  } catch { /* Storage can be unavailable; unscoped keys are never read. */ }
}

function storageKey(key: string, scope: ResumableScope) {
  clearLegacyResumableRecords()
  const normalized = createResumableScope(scope.apiBaseUrl, scope.userId)
  return `${key}.v2:${encodeURIComponent(JSON.stringify([normalized.apiBaseUrl, normalized.userId]))}`
}

export interface ResumableUploadRecord {
  sessionId: string
  teamId?: number | null
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
  teamId?: number | null
  parentId: number | null
  fileName: string
  displayName: string
  fileSize: number
  md5Hash: string
}

export interface ResumableDirectoryRecord {
  pathKey: string
  teamId?: number | null
  parentPathKey: string | null
  baseParentId: number | null
  remoteId: number
  remoteName: string
  createdAt: number
  updatedAt: number
}

export function findResumableUpload(scope: ResumableScope, identity: UploadRecordIdentity) {
  const records = readResumableUploads(scope)
  return records.find(record => isSameUpload(record, identity)) || null
}

export function saveResumableUpload(scope: ResumableScope, record: ResumableUploadRecord) {
  const records = readResumableUploads(scope).filter(existing =>
    existing.sessionId !== record.sessionId
    && !isSameUpload(existing, record),
  )
  records.push({ ...record, updatedAt: Date.now() })
  writeResumableUploads(scope, records)
}

export function removeResumableUpload(scope: ResumableScope, sessionId: string | undefined) {
  if (!sessionId) return
  writeResumableUploads(scope, readResumableUploads(scope).filter(record => record.sessionId !== sessionId))
}

export function readResumableUploads(scope: ResumableScope) {
  const now = Date.now()
  try {
    const parsed = JSON.parse(localStorage.getItem(storageKey(STORAGE_KEY, scope)) || '[]') as ResumableUploadRecord[]
    const records = Array.isArray(parsed) ? parsed.filter(isValidRecord) : []
    const fresh = records.filter(record => now - record.createdAt < SESSION_TTL_MS)
    if (fresh.length !== records.length) writeResumableUploads(scope, fresh)
    return fresh
  } catch {
    return []
  }
}

export function findResumableDirectory(scope: ResumableScope, pathKey: string, teamId: number | null = null) {
  return readResumableDirectories(scope).find(record => record.pathKey === pathKey && (record.teamId ?? null) === teamId) || null
}

export function saveResumableDirectory(scope: ResumableScope, record: ResumableDirectoryRecord) {
  const records = readResumableDirectories(scope).filter(existing => existing.pathKey !== record.pathKey || (existing.teamId ?? null) !== (record.teamId ?? null))
  records.push({ ...record, updatedAt: Date.now() })
  writeResumableDirectories(scope, records)
}

export function readResumableDirectories(scope: ResumableScope) {
  const now = Date.now()
  try {
    const parsed = JSON.parse(localStorage.getItem(storageKey(DIRECTORY_STORAGE_KEY, scope)) || '[]') as ResumableDirectoryRecord[]
    const records = Array.isArray(parsed) ? parsed.filter(isValidDirectoryRecord) : []
    const fresh = records.filter(record => now - record.createdAt < SESSION_TTL_MS)
    if (fresh.length !== records.length) writeResumableDirectories(scope, fresh)
    return fresh
  } catch {
    return []
  }
}

function writeResumableUploads(scope: ResumableScope, records: ResumableUploadRecord[]) {
  try {
    const key = storageKey(STORAGE_KEY, scope)
    if (records.length) localStorage.setItem(key, JSON.stringify(records))
    else localStorage.removeItem(key)
  } catch {
    // ignore unavailable storage
  }
}

function writeResumableDirectories(scope: ResumableScope, records: ResumableDirectoryRecord[]) {
  try {
    const key = storageKey(DIRECTORY_STORAGE_KEY, scope)
    if (records.length) localStorage.setItem(key, JSON.stringify(records))
    else localStorage.removeItem(key)
  } catch {
    // ignore unavailable storage
  }
}

function isSameUpload(record: UploadRecordIdentity, identity: UploadRecordIdentity) {
  return (record.teamId ?? null) === (identity.teamId ?? null)
    && record.parentId === identity.parentId
    && record.fileName === identity.fileName
    && record.displayName === identity.displayName
    && record.fileSize === identity.fileSize
    && record.md5Hash === identity.md5Hash
}

function isValidRecord(record: ResumableUploadRecord) {
  return !!record
    && typeof record.sessionId === 'string'
    && (record.teamId === undefined || record.teamId === null || typeof record.teamId === 'number')
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
    && (record.teamId === undefined || record.teamId === null || typeof record.teamId === 'number')
    && (record.parentPathKey === null || typeof record.parentPathKey === 'string')
    && (record.baseParentId === null || typeof record.baseParentId === 'number')
    && typeof record.remoteId === 'number'
    && typeof record.remoteName === 'string'
    && typeof record.createdAt === 'number'
    && typeof record.updatedAt === 'number'
}
