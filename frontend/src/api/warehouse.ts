import api from './client'
import type { ApiResponse, PageResult } from '@/types/api'

export interface WarehouseFilter {
  start: string
  end: string
  scope: 'ALL' | 'PERSONAL' | 'TEAM'
  teamId?: number
  generationId?: string
}
export interface WarehouseMeta {
  timezone: string
  startDate: string
  endDate: string
  scope: WarehouseFilter['scope']
  scopeId: number
  generationId: string | null
  publishedAt: string | null
  dataThrough: string | null
  coverageStart: string | null
  stale: boolean
  status: 'READY' | 'UNINITIALIZED'
}
export type WarehouseMetrics = Record<string, number | string>
export interface WarehouseOverview {
  meta: WarehouseMeta
  usage: WarehouseMetrics
  capacity: WarehouseMetrics
}
export interface WarehouseDay {
  day: string
  usage: WarehouseMetrics | null
  capacity: WarehouseMetrics | null
}
export interface WarehouseTrends { meta: WarehouseMeta; days: WarehouseDay[] }
export interface WarehouseTeam {
  team_id: number
  logical_bytes: number | null
  file_count: number | null
  member_count: number | null
  sampled_day: string | null
  uploads: number
  uploaded_logical_bytes: number
  download_attempts: number
  download_written_bytes: number
  active_users: number
}
export interface WarehouseTeams { meta: WarehouseMeta; teams: PageResult<WarehouseTeam> }
export interface WarehouseRun {
  run_id: string
  job: 'hourly' | 'snapshot' | 'backfill'
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  started_at: number
  updated_at: number
  error_key: string
}

export const overview = (params: WarehouseFilter, signal?: AbortSignal) =>
  api.get<ApiResponse<WarehouseOverview>>('/admin/warehouse/overview', { params, signal, suppressToast: true })
export const trends = (params: WarehouseFilter, signal?: AbortSignal) =>
  api.get<ApiResponse<WarehouseTrends>>('/admin/warehouse/trends', { params, signal, suppressToast: true })
export const teams = (params: WarehouseFilter, page: number, signal?: AbortSignal) =>
  api.get<ApiResponse<WarehouseTeams>>('/admin/warehouse/teams', { params: { ...params, page, size: 20 }, signal, suppressToast: true })
export const runs = (signal?: AbortSignal) =>
  api.get<ApiResponse<WarehouseRun[]>>('/admin/warehouse/runs', { signal, suppressToast: true })
