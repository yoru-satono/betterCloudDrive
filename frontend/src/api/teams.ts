import api from './client'
import type { ApiResponse, PageResult } from '@/types/api'
import type { FileEntity } from '@/types/file'
import type { CreateShareRequest, ShareLinkEntity, SharePasswordResponse, UpdateShareRequest } from '@/types/share'
import type {
  Team,
  TeamActivity,
  TeamEffectivePermission,
  TeamFilePermission,
  TeamInvite,
  TeamMember,
  TeamOperationLog,
  TeamRole,
  TeamTask,
} from '@/types/team'
import type { TagEntity } from '@/types/tag'

export interface ListTeamFilesParams {
  parentId?: number | null
  page?: number
  size?: number
  sortBy?: string
  order?: string
}

export const listTeams = (page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<Team>>>('/teams', { params: { page, size } })

export const createTeam = (name: string) =>
  api.post<ApiResponse<Team>>('/teams', { name })

export const getTeam = (teamId: number) =>
  api.get<ApiResponse<Team>>(`/teams/${teamId}`)

export const updateTeam = (teamId: number, name: string) =>
  api.put<ApiResponse<Team>>(`/teams/${teamId}`, { name })

export const deleteTeam = (teamId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}`)

export const leaveTeam = (teamId: number) =>
  api.post<ApiResponse<void>>(`/teams/${teamId}/leave`)

export const transferTeamOwner = (teamId: number, userId: number) =>
  api.post<ApiResponse<Team>>(`/teams/${teamId}/owner`, { userId })

export const listMembers = (teamId: number, page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<TeamMember>>>(`/teams/${teamId}/members`, { params: { page, size } })

export const addMember = (teamId: number, username: string, role: Exclude<TeamRole, 'OWNER'>) =>
  api.post<ApiResponse<TeamMember>>(`/teams/${teamId}/members`, { username, role })

export const updateMember = (teamId: number, userId: number, role: Exclude<TeamRole, 'OWNER'>) =>
  api.put<ApiResponse<TeamMember>>(`/teams/${teamId}/members/${userId}`, { role })

export const removeMember = (teamId: number, userId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/members/${userId}`)

export const createInvite = (teamId: number, email: string, role: Exclude<TeamRole, 'OWNER'>) =>
  api.post<ApiResponse<TeamInvite>>(`/teams/${teamId}/invites`, { email, role })

export const listInvites = (teamId: number, page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<TeamInvite>>>(`/teams/${teamId}/invites`, { params: { page, size } })

async function collectPages<T>(load: (page: number, size: number) => Promise<{ data: ApiResponse<PageResult<T>> }>) {
  const records: T[] = []
  let page = 1
  let pages = 1
  do {
    const { data } = await load(page, 100)
    records.push(...data.data.records)
    pages = data.data.pages
    page++
  } while (page <= pages)
  return records
}

export const listAllTeams = () => collectPages((page, size) => listTeams(page, size))
export const listAllMembers = (teamId: number) => collectPages((page, size) => listMembers(teamId, page, size))
export const listAllInvites = (teamId: number) => collectPages((page, size) => listInvites(teamId, page, size))

export const resendInvite = (teamId: number, inviteId: number) =>
  api.post<ApiResponse<TeamInvite>>(`/teams/${teamId}/invites/${inviteId}/resend`)

export const cancelInvite = (teamId: number, inviteId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/invites/${inviteId}`)

export const getInvite = (token: string) =>
  api.get<ApiResponse<TeamInvite>>(`/team-invites/${token}`)

export const acceptInvite = (token: string) =>
  api.post<ApiResponse<void>>(`/team-invites/${token}/accept`)

export const rejectInvite = (token: string) =>
  api.post<ApiResponse<void>>(`/team-invites/${token}/reject`)

export const listTeamAuditLogs = (teamId: number, params: {
  actorUserId?: number
  action?: string
  targetType?: string
  result?: number
  startAt?: string
  endAt?: string
  page?: number
  size?: number
} = {}) =>
  api.get<ApiResponse<PageResult<TeamOperationLog>>>(`/teams/${teamId}/audit-logs`, { params })

export const exportTeamAuditLogs = (teamId: number, params: {
  actorUserId?: number
  action?: string
  targetType?: string
  result?: number
  startAt?: string
  endAt?: string
} = {}) =>
  api.get<Blob>(`/teams/${teamId}/audit-logs/export`, { params, responseType: 'blob' })

export const queueTeamAuditExport = (teamId: number, params: {
  actorUserId?: number
  action?: string
  targetType?: string
  result?: number
  startAt?: string
  endAt?: string
} = {}) =>
  api.post<ApiResponse<TeamTask>>(`/teams/${teamId}/audit-logs/export-tasks`, undefined, { params })

export const listTeamActivities = (teamId: number, page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<TeamActivity>>>(`/teams/${teamId}/activities`, { params: { page, size } })

export const listTeamTasks = (teamId: number, params: {
  status?: string
  type?: string
  page?: number
  size?: number
} = {}) =>
  api.get<ApiResponse<PageResult<TeamTask>>>(`/teams/${teamId}/tasks`, { params })

export const retryTeamTask = (teamId: number, taskId: number) =>
  api.post<ApiResponse<TeamTask>>(`/teams/${teamId}/tasks/${taskId}/retry`)

export const downloadTeamTaskResult = (teamId: number, taskId: number) =>
  api.get<Blob>(`/teams/${teamId}/tasks/${taskId}/download`, { responseType: 'blob' })

export const addTeamFavorite = (teamId: number, fileId: number) =>
  api.post<ApiResponse<void>>(`/teams/${teamId}/favorites/${fileId}`)

export const removeTeamFavorite = (teamId: number, fileId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/favorites/${fileId}`)

export const isTeamFavorite = (teamId: number, fileId: number) =>
  api.get<ApiResponse<boolean>>(`/teams/${teamId}/favorites/${fileId}/status`)

export const listTeamFavorites = (teamId: number, page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/teams/${teamId}/favorites`, { params: { page, size, q } })

export const createTeamTag = (teamId: number, tagName: string, color?: string) =>
  api.post<ApiResponse<TagEntity>>(`/teams/${teamId}/tags`, { tagName, color })

export const listTeamTags = (teamId: number, q?: string) =>
  api.get<ApiResponse<TagEntity[]>>(`/teams/${teamId}/tags`, { params: { q } })

export const updateTeamTag = (teamId: number, tagId: number, tagName: string, color?: string) =>
  api.put<ApiResponse<TagEntity>>(`/teams/${teamId}/tags/${tagId}`, { tagName, color })

export const deleteTeamTag = (teamId: number, tagId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/tags/${tagId}`)

export const addTeamFileTag = (teamId: number, fileId: number, tagId: number) =>
  api.post<ApiResponse<void>>(`/teams/${teamId}/tags/${tagId}/files`, { fileIds: [fileId] })

export const removeTeamFileTag = (teamId: number, fileId: number, tagId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/tags/${tagId}/files/${fileId}`)

export const listTeamFilesByTag = (teamId: number, tagId: number, page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/teams/${teamId}/tags/${tagId}/files`, { params: { page, size, q } })

export const listTeamFiles = (teamId: number, params: ListTeamFilesParams) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/teams/${teamId}/files`, { params })

export const getTeamFile = (teamId: number, fileId: number) =>
  api.get<ApiResponse<FileEntity>>(`/teams/${teamId}/files/${fileId}`)

export const getTeamFileEffectivePermission = (teamId: number, fileId: number) =>
  api.get<ApiResponse<TeamEffectivePermission>>(`/teams/${teamId}/files/${fileId}/effective-permission`)

export const listTeamFilePermissions = (teamId: number, fileId: number) =>
  api.get<ApiResponse<TeamFilePermission[]>>(`/teams/${teamId}/files/${fileId}/permissions`)

export const upsertTeamFilePermission = (
  teamId: number,
  fileId: number,
  request: {
    subjectType: 'USER' | 'ROLE'
    subjectUserId?: number | null
    subjectRole?: Exclude<TeamRole, 'OWNER'> | null
    canRead: boolean
    canWrite: boolean
    canShare: boolean
    canDelete: boolean
    canManage: boolean
  },
) =>
  api.put<ApiResponse<TeamFilePermission>>(`/teams/${teamId}/files/${fileId}/permissions`, request)

export const deleteTeamFilePermission = (teamId: number, fileId: number, permissionId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/files/${fileId}/permissions/${permissionId}`)

export const searchTeamFiles = (teamId: number, q: string, page = 1, size = 20) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/teams/${teamId}/files/search`, { params: { q, page, size } })

export const createTeamFolder = (teamId: number, parentId: number | null, folderName: string) =>
  api.post<ApiResponse<FileEntity>>(`/teams/${teamId}/files/folder`, { parentId, folderName })

export const renameTeamFile = (teamId: number, fileId: number, newName: string) =>
  api.put<ApiResponse<FileEntity>>(`/teams/${teamId}/files/${fileId}`, { newName })

export const moveTeamFile = (teamId: number, fileId: number, targetParentId: number | null) =>
  api.post<ApiResponse<void>>(`/teams/${teamId}/files/${fileId}/move`, { targetParentId })

export const deleteTeamFiles = (teamId: number, fileIds: number[]) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/files`, { data: { fileIds } })

export const listTeamRecycleBin = (teamId: number, page = 1, size = 20, q?: string) =>
  api.get<ApiResponse<PageResult<FileEntity>>>(`/teams/${teamId}/recycle-bin`, { params: { page, size, q } })

export const restoreTeamFiles = (teamId: number, fileIds: number[]) =>
  Promise.all(fileIds.map(fileId => api.post<ApiResponse<void>>(`/teams/${teamId}/recycle-bin/${fileId}/restore`)))

export const permanentDeleteTeamFiles = (teamId: number, fileIds: number[]) =>
  Promise.all(fileIds.map(fileId => api.delete<ApiResponse<void>>(`/teams/${teamId}/recycle-bin/${fileId}`)))

export const emptyTeamRecycleBin = (teamId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/recycle-bin`)

export const createTeamShare = (teamId: number, req: CreateShareRequest) =>
  api.post<ApiResponse<ShareLinkEntity>>(`/teams/${teamId}/shares`, req)

export const listTeamShares = (teamId: number, page = 1, size = 20, q?: string, passwordProtected?: boolean, expirationStatus?: 'ACTIVE' | 'EXPIRED' | 'NEVER') =>
  api.get<ApiResponse<PageResult<ShareLinkEntity>>>(`/teams/${teamId}/shares`, { params: { page, size, q, passwordProtected, expirationStatus } })

export const getTeamShare = (teamId: number, shareId: number) =>
  api.get<ApiResponse<ShareLinkEntity>>(`/teams/${teamId}/shares/${shareId}`)

export const getTeamSharePassword = (teamId: number, shareId: number) =>
  api.get<ApiResponse<SharePasswordResponse>>(`/teams/${teamId}/shares/${shareId}/password`)

export const updateTeamShare = (teamId: number, shareId: number, req: UpdateShareRequest) =>
  api.put<ApiResponse<ShareLinkEntity>>(`/teams/${teamId}/shares/${shareId}`, req)

export const deleteTeamShare = (teamId: number, shareId: number) =>
  api.delete<ApiResponse<void>>(`/teams/${teamId}/shares/${shareId}`)
