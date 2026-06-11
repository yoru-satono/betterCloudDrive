import api from './client'
import type { ShareLinkEntity, CreateShareRequest } from '@/types/share'
import type { ApiResponse, PageResult } from '@/types/api'
import type { FileEntity } from '@/types/file'

export const createShare = (req: CreateShareRequest) =>
  api.post<ApiResponse<ShareLinkEntity>>('/shares', req)

export const listShares = (page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<ShareLinkEntity>>>('/shares', { params: { page, size } })

export const deleteShare = (shareId: number) =>
  api.delete<ApiResponse<void>>(`/shares/${shareId}`)

export const accessShare = (shareCode: string, password?: string) =>
  api.get<ApiResponse<{ file: FileEntity; share: ShareLinkEntity }>>(`/shares/access/${shareCode}`, { params: { password } })

export const getShareDownloadUrl = (shareCode: string, password?: string) =>
  api.get<ApiResponse<{ url: string; fileName: string }>>(`/shares/access/${shareCode}/download`, { params: { password } })
