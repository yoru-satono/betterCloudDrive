export interface ShareLinkEntity {
  id: number
  fileId: number
  shareCode: string
  shareUrl: string
  expiresAt: string | null
  visitCount: number
  maxVisits: number | null
  isPasswordProtected: boolean
  createdAt: string
}

export interface CreateShareRequest {
  fileId: number
  expiresAt?: string
  maxVisits?: number
  password?: string
}
