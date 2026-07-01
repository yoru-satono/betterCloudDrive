export type TeamRole = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'

export interface Team {
  id: number
  name: string
  ownerId: number
  storageQuota: number
  storageUsed: number
  role: TeamRole
  createdAt: string
  updatedAt: string
  archivedAt?: string | null
  archivedBy?: number | null
  deletedAt?: string | null
  deletedBy?: number | null
  purgeAfter?: string | null
}

export interface TeamMember {
  teamId: number
  userId: number
  username: string
  email: string | null
  role: TeamRole
  status: number
  createdAt: string
  updatedAt: string
}

export interface TeamInvite {
  id: number
  teamId: number
  teamName: string | null
  email: string
  role: Exclude<TeamRole, 'OWNER'>
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELED' | 'EXPIRED'
  invitedByUserId: number
  invitedByUsername: string | null
  expiresAt: string
  acceptedAt: string | null
  rejectedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface TeamOperationLog {
  id: number
  teamId: number
  actorUserId: number | null
  action: string
  targetType: string | null
  targetId: number | null
  result: number
  statusCode: number | null
  requestId: string | null
  traceId: string | null
  details: string | null
  createdAt: string
}

export interface TeamEffectivePermission {
  canRead: boolean
  canWrite: boolean
  canShare: boolean
  canDelete: boolean
  canManage: boolean
}

export interface TeamFilePermission {
  id: number
  teamId: number
  fileId: number
  subjectType: 'USER' | 'ROLE'
  subjectUserId: number | null
  subjectRole: Exclude<TeamRole, 'OWNER'> | null
  canRead: boolean
  canWrite: boolean
  canShare: boolean
  canDelete: boolean
  canManage: boolean
  createdBy: number | null
  createdAt: string
  updatedAt: string
}
