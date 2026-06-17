export interface ShareLinkEntity {
  id: number
  userId: number
  fileId: number
  shareCode: string
  hasPassword: boolean
  expireAt: string | null
  maxVisits: number | null
  downloadCount: number
  visitCount: number
  isCanceled: boolean
  createdAt: string
  updatedAt: string
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
}

export interface AccessShareResponse {
  fileId: number
  fileName: string
  fileType: 'file' | 'folder'
  fileSize: number
}
