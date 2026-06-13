export interface ShareLinkEntity {
  id: number
  userId: number
  fileId: number
  shareCode: string
  passwordHash: string | null
  expireAt: string | null
  maxVisits: number | null
  downloadCount: number
  visitCount: number
  isCanceled: boolean
  createdAt: string
  updatedAt: string
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
}

export interface AccessShareResponse {
  fileId: number
  fileName: string
  fileType: 'file' | 'folder'
  fileSize: number
}
