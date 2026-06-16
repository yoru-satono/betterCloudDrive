export interface UserEntity {
  id: number
  username: string
  email: string | null
  emailVerified?: boolean
  nickname?: string | null
  avatarUrl?: string | null
  role: 'ROLE_USER' | 'ROLE_ADMIN'
  storageUsed: number
  storageQuota: number
  webdavEnabled?: boolean
  status: number
  createdAt: string
  updatedAt: string
  deletedAt?: string | null
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}
