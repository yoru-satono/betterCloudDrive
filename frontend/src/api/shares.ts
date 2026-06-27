import api from './client'
import type { AccessShareResponse, ShareLinkEntity, SharePasswordResponse, CreateShareRequest, UpdateShareRequest } from '@/types/share'
import type { ApiResponse, PageResult } from '@/types/api'
import type { FileEntity } from '@/types/file'

export interface SaveSharedItemRequest {
  fileId?: number
  targetParentId?: number | null
  password?: string
}

export const createShare = (req: CreateShareRequest) =>
  api.post<ApiResponse<ShareLinkEntity>>('/shares', req)

export const listShares = (page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<ShareLinkEntity>>>('/shares', { params: { page, size } })

export const getShare = (shareId: number) =>
  api.get<ApiResponse<ShareLinkEntity>>(`/shares/${shareId}`)

export const getSharePassword = (shareId: number) =>
  api.get<ApiResponse<SharePasswordResponse>>(`/shares/${shareId}/password`)

export const updateShare = (shareId: number, req: UpdateShareRequest) =>
  api.put<ApiResponse<ShareLinkEntity>>(`/shares/${shareId}`, req)

export const deleteShare = (shareId: number) =>
  api.delete<ApiResponse<void>>(`/shares/${shareId}`)

export const accessShare = (shareCode: string, password?: string) =>
  api.post<ApiResponse<AccessShareResponse>>(`/shares/access/${shareCode}`, password ? { password } : undefined, { suppressToast: true })

export const listSharedFiles = (shareCode: string, parentId?: number | null, page = 1, size = 20, password?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/shares/access/${shareCode}/files`, {
    params: { parentId: parentId ?? undefined, page, size, password },
    suppressToast: true
  })

export const saveSharedItem = (shareCode: string, req: SaveSharedItemRequest) =>
  api.post<ApiResponse<FileEntity>>(`/shares/access/${shareCode}/save`, req, { suppressToast: true })
