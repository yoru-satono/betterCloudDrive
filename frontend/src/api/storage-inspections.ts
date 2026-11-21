import api from './client'
import type { ApiResponse, PageResult } from '@/types/api'

const base = '/admin/storage-inspections'
const options = (signal?: AbortSignal) => ({ signal, suppressToast: true })
export const findingTypes = ['MISSING_OBJECT', 'OBJECT_SIZE_MISMATCH', 'AUXILIARY_MISSING', 'ORPHAN_OBJECT',
  'UNMANAGED_OBJECT', 'QUOTA_DRIFT', 'QUOTA_METADATA_INVALID', 'UPLOAD_STALLED', 'TASK_STALLED', 'METADATA_INCONSISTENCY'] as const
export type FindingType = typeof findingTypes[number]
export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELED'
export interface InspectionRun {
  id: string
  kind: 'FULL' | 'RECHECK'
  findingId: string | null
  status: RunStatus
  phase: string
  ruleVersion: number
  requestedBy: number | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  metadataAt: string | null
  scanStartedAt: string | null
  scanFinishedAt: string | null
  metadataComplete: boolean
  inventoryComplete: boolean
  cancelRequested: boolean
  objectsScanned: number
  objectBytes: number
  referencesScanned: number
  candidatesChecked: number
  errorKey: string | null
}
export interface InspectionFinding {
  id: string
  type: FindingType
  referenceKind: string
  referenceId: string | null
  objectFingerprint: string | null
  scope: 'PERSONAL' | 'TEAM' | null
  ownerId: number | null
  severity: 'INFO' | 'WARNING' | 'ERROR'
  status: 'SUSPECTED' | 'CONFIRMED' | 'RESOLVED'
  reviewStatus: 'OPEN' | 'ACKNOWLEDGED' | 'IGNORED'
  firstSeenAt: string
  stableSince: string
  lastObservedAt: string
  observations: number
  expectedBytes: number | null
  observedBytes: number | null
  objectModifiedAt: string | null
  lastSeenRun: string
  resolvedAt: string | null
  metadataReason?: string | null
}
export interface InspectionOverview {
  enabled: boolean
  accountingReady: boolean
  scheduled: boolean
  zone: string
  nextScheduledAt: string | null
  latestRun: InspectionRun | null
  latestComplete: InspectionRun | null
  counts: Array<{ type: FindingType; status: InspectionFinding['status']; severity: InspectionFinding['severity']; count: number }>
}
export interface Review { id: string; actorId: number; action: string; reason: string; createdAt: string }
export interface RepairPreview {
  id: string; findingId: string; actorId: number; scope: 'PERSONAL' | 'TEAM'; ownerId: number
  recordedBytes: number; expectedBytes: number; quotaRevision: number; createdAt: string; expiresAt: string
}
export interface Repair {
  id: string; previewId: string; findingId: string; actorId: number; idempotencyKey: string; scope: string
  ownerId: number; usedBefore: number; usedAfter: number; quotaRevisionBefore: number; reason: string; ruleVersion: number; createdAt: string
}
export interface FindingDetail { finding: InspectionFinding; objectKey: string | null; reviews: Review[]; repairs: Repair[] }
export interface FindingFilter { type?: FindingType; status?: InspectionFinding['status']; reviewStatus?: InspectionFinding['reviewStatus']; scope?: 'PERSONAL' | 'TEAM'; ownerId?: number }
export interface RepairRequest { previewId: string; idempotencyKey: string; reason: string }

export const overview = (signal?: AbortSignal) => api.get<ApiResponse<InspectionOverview>>(`${base}/overview`, options(signal))
export const runs = (page = 1, size = 5, signal?: AbortSignal) =>
  api.get<ApiResponse<PageResult<InspectionRun>>>(`${base}/runs`, { ...options(signal), params: { page, size } })
export const start = () => api.post<ApiResponse<InspectionRun>>(`${base}/runs`, undefined, options())
export const cancel = (id: string) => api.post<ApiResponse<InspectionRun>>(`${base}/runs/${id}/cancel`, undefined, options())
export const findings = (filter: FindingFilter, page: number, signal?: AbortSignal) =>
  api.get<ApiResponse<PageResult<InspectionFinding>>>(`${base}/findings`, { ...options(signal), params: { ...filter, page, size: 20 } })
export const detail = (id: string, signal?: AbortSignal) => api.get<ApiResponse<FindingDetail>>(`${base}/findings/${id}`, options(signal))
export const recheck = (id: string) => api.post<ApiResponse<InspectionRun>>(`${base}/findings/${id}/rechecks`, undefined, options())
export const review = (id: string, action: string, reason: string) =>
  api.post<ApiResponse<Review>>(`${base}/findings/${id}/reviews`, { action, reason }, options())
export const preview = (id: string) => api.post<ApiResponse<RepairPreview>>(`${base}/findings/${id}/repair-preview`, undefined, options())
export const repair = (request: RepairRequest) => api.post<ApiResponse<Repair>>(`${base}/quota-repairs`, request, options())
