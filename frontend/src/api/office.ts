import api from './client'
import type { ApiResponse } from '@/types/api'
import type { OnlyOfficeConfigResponse } from '@/types/office'

export const getOfficeConfig = (fileId: number) =>
  api.get<ApiResponse<OnlyOfficeConfigResponse>>(`/office/files/${fileId}/config`)

export const getTeamOfficeConfig = (teamId: number, fileId: number) =>
  api.get<ApiResponse<OnlyOfficeConfigResponse>>(`/teams/${teamId}/office/files/${fileId}/config`)
