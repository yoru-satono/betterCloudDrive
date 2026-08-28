export interface ShareLinkEntity {
  id: number
  userId: number
  teamId: number | null
  fileId: number
  shareCode: string
  shareUrl?: string | null
  hasPassword: boolean
  expireAt: string | null
  expireAtEpochMs?: number | null
  maxVisits: number | null
  downloadCount: number
  visitCount: number
  isCanceled: boolean
  createdAt: string
  updatedAt: string
  fileName?: string | null
  fileType?: 'file' | 'folder' | null
  fileSize?: number | null
}

export interface SharePasswordResponse {
  password: string | null
}

export interface CreateShareRequest {
  fileId: number
  expireAt?: number
  maxVisits?: number
  password?: string
  notifyEmail?: string
}

export interface UpdateShareRequest {
  expireAt?: number
  maxVisits?: number
  password?: string
  clearPassword?: boolean
  clearExpireAt?: boolean
  clearMaxVisits?: boolean
}

export interface AccessShareResponse {
  fileId: number
  fileName: string
  fileType: 'file' | 'folder'
  fileSize: number
}
