export interface UserEntity {
  id: number
  username: string
  email: string | null
  role: 'ROLE_USER' | 'ROLE_ADMIN'
  storageUsed: number
  storageQuota: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: UserEntity
}
