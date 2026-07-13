import { APIRequestContext, Browser, BrowserContext, expect, Page } from '@playwright/test'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'

export interface E2EUser {
  userId: number
  username: string
  password: string
  email: string
  accessToken: string
  refreshToken: string
}

export interface E2EFile {
  id: number
  fileName: string
  fileType: 'file' | 'folder'
  parentId: number | null
  fileSize: number
  md5Hash?: string | null
  versionCount?: number
}

export interface E2EFileVersion {
  id: number
  fileId: number
  versionNumber: number
  fileSize: number
  md5Hash?: string | null
  createdAt?: string | null
}

export interface E2EShare {
  id: number
  shareCode: string
  fileId: number
  visitCount: number
  downloadCount: number
  maxVisits?: number | null
  hasPassword?: boolean
}

export interface E2ETeam {
  id: number
  name: string
  ownerId: number
  role: 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'
  storageQuota: number
  storageUsed: number
}

export interface E2ETeamMember {
  teamId: number
  userId: number
  username?: string | null
  email?: string | null
  role: 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'
  status: number
}

export interface E2ETeamInvite {
  id: number
  teamId: number
  teamName: string | null
  email: string
  role: Exclude<E2ETeamMember['role'], 'OWNER'>
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELED' | 'EXPIRED'
  token?: string | null
  expiresAt: string
  createdAt: string
  updatedAt: string
}

export interface E2ENotification {
  id: number
  userId: number
  teamId: number | null
  type: string
  titleKey: string
  contentKey: string
  messageParams: Record<string, string | number | boolean | null>
  link: string | null
  isRead: boolean
  readAt: string | null
  createdAt: string
}

export const backendURL = process.env.E2E_BACKEND_URL || 'http://localhost:8080'
export const frontendURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
export const mailpitURL = process.env.E2E_MAILPIT_URL || 'http://localhost:8025'
const postgresHost = process.env.E2E_POSTGRES_HOST || '127.0.0.1'
const postgresPort = Number(process.env.E2E_POSTGRES_PORT || '5432')
const postgresUser = process.env.POSTGRES_USER || 'bcd_user'
const postgresPassword = process.env.POSTGRES_PASSWORD || 'change_me_in_production'
const postgresDatabase = process.env.POSTGRES_DB || 'better_cloud_drive'

export function uniqueName(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}

export async function createUser(request: APIRequestContext, email?: string): Promise<E2EUser> {
  const username = uniqueName('e2e')
  const password = 'TestPass123!'
  const userEmail = email ?? `${username}@test.local`

  await clearMailpit(request)
  const sendCode = await request.post(`${backendURL}/api/v1/auth/register-code/send`, {
    data: { email: userEmail },
  })
  await expect(sendCode, await sendCode.text()).toBeOK()
  const code = await getLatestVerificationCode(request, userEmail)

  const register = await request.post(`${backendURL}/api/v1/auth/register`, {
    data: { username, password, email: userEmail, verificationCode: code },
  })
  await expect(register, await register.text()).toBeOK()
  const registerBody = await register.json()
  expect(registerBody.code).toBe(200)

  const loginBody = await loginByUsername(request, username, password)

  return {
    userId: registerBody.data.userId,
    username,
    password,
    email: userEmail,
    accessToken: loginBody.data.accessToken,
    refreshToken: loginBody.data.refreshToken,
  }
}

export async function loginByUsername(request: APIRequestContext, username: string, password: string) {
  const login = await request.post(`${backendURL}/api/v1/auth/login`, {
    data: { username, password },
  })
  await expect(login, await login.text()).toBeOK()
  const body = await login.json()
  expect(body.code).toBe(200)
  return body as { code: number; data: { accessToken: string; refreshToken: string; expiresIn: number } }
}

export async function refreshUserLogin(request: APIRequestContext, user: E2EUser) {
  const loginBody = await loginByUsername(request, user.username, user.password)
  user.accessToken = loginBody.data.accessToken
  user.refreshToken = loginBody.data.refreshToken
  return user
}

export async function createAuthenticatedContext(browser: Browser, user: E2EUser): Promise<BrowserContext> {
  return browser.newContext({
    storageState: {
      cookies: [],
      origins: [
        {
          origin: frontendURL,
          localStorage: [
            { name: 'accessToken', value: user.accessToken },
            { name: 'refreshToken', value: user.refreshToken },
          ],
        },
      ],
    },
  })
}

export async function loginViaUi(page: Page, user: E2EUser) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(user.username)
  await page.getByLabel('密码').fill(user.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/files$/)
}

export async function createFolder(
  request: APIRequestContext,
  token: string,
  folderName = uniqueName('folder'),
  parentId: number | null = null,
) {
  const res = await request.post(`${backendURL}/api/v1/files/folder`, {
    headers: authHeaders(token),
    data: { parentId, folderName },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EFile
}

export async function uploadSampleFile(
  request: APIRequestContext,
  token: string,
  parentId: number | null,
  fileName = uniqueName('sample') + '.txt',
  content?: Buffer | string,
) {
  return uploadToApi(request, token, '/upload', parentId, fileName, content)
}

export async function createShare(
  request: APIRequestContext,
  token: string,
  fileId: number,
  options: { password?: string; maxVisits?: number; expireAt?: number; notifyEmail?: string } = {},
) {
  const res = await request.post(`${backendURL}/api/v1/shares`, {
    headers: authHeaders(token),
    data: { fileId, ...options },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EShare
}

export async function getShare(request: APIRequestContext, token: string, shareId: number) {
  const res = await request.get(`${backendURL}/api/v1/shares/${shareId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EShare
}

export async function listShares(request: APIRequestContext, token: string) {
  const res = await request.get(`${backendURL}/api/v1/shares`, {
    headers: authHeaders(token),
    params: { page: '1', size: '100' },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2EShare[]
}

export async function getSharePassword(request: APIRequestContext, token: string, shareId: number) {
  const res = await request.get(`${backendURL}/api/v1/shares/${shareId}/password`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as { password: string | null }
}

export async function updateShare(
  request: APIRequestContext,
  token: string,
  shareId: number,
  data: { password?: string; maxVisits?: number; expireAt?: number },
) {
  const res = await request.put(`${backendURL}/api/v1/shares/${shareId}`, {
    headers: authHeaders(token),
    data,
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EShare
}

export async function accessShare(request: APIRequestContext, shareCode: string, password?: string) {
  const res = await request.post(`${backendURL}/api/v1/shares/access/${shareCode}`, {
    data: password ? { password } : undefined,
  })
  const body = await res.json()
  return { res, body }
}

export async function listSharedFiles(
  request: APIRequestContext,
  shareCode: string,
  options: { parentId?: number | null; page?: number; size?: number; password?: string } = {},
) {
  const params: Record<string, string> = {
    page: String(options.page ?? 1),
    size: String(options.size ?? 100),
  }
  if (options.parentId !== undefined && options.parentId !== null) params.parentId = String(options.parentId)
  if (options.password !== undefined) params.password = options.password
  const res = await request.get(`${backendURL}/api/v1/shares/access/${shareCode}/files`, { params })
  const body = await res.json()
  return { res, body }
}

export async function moveFile(
  request: APIRequestContext,
  token: string,
  fileId: number,
  targetParentId: number | null,
) {
  const res = await request.post(`${backendURL}/api/v1/files/${fileId}/move`, {
    headers: authHeaders(token),
    data: { targetParentId },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
}

export async function copyFile(
  request: APIRequestContext,
  token: string,
  fileId: number,
  targetParentId: number | null,
) {
  const res = await request.post(`${backendURL}/api/v1/files/${fileId}/copy`, {
    headers: authHeaders(token),
    data: { targetParentId },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
}

export async function saveSharedItem(
  request: APIRequestContext,
  token: string,
  shareCode: string,
  data: { fileId?: number; targetParentId?: number | null; password?: string },
) {
  const res = await request.post(`${backendURL}/api/v1/shares/access/${shareCode}/save`, {
    headers: authHeaders(token),
    data,
  })
  const body = await res.json()
  return { res, body }
}

export async function listFiles(
  request: APIRequestContext,
  token: string,
  parentId: number | null = null,
) {
  const params: Record<string, string> = { page: '1', size: '100' }
  if (parentId !== null) params.parentId = String(parentId)
  const res = await request.get(`${backendURL}/api/v1/files`, {
    headers: authHeaders(token),
    params,
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2EFile[]
}

export async function getFile(request: APIRequestContext, token: string, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/files/${fileId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EFile
}

export async function getOfficeConfig(request: APIRequestContext, token: string, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/office/files/${fileId}/config`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as { documentServerUrl: string; editable: boolean; config: Record<string, unknown> }
}

export async function downloadFileBytes(request: APIRequestContext, token: string, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/download/${fileId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  return res.body()
}

export async function createTeam(request: APIRequestContext, token: string, name = uniqueName('team')) {
  const res = await request.post(`${backendURL}/api/v1/teams`, {
    headers: authHeaders(token),
    data: { name },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeam
}

export async function getTeam(request: APIRequestContext, token: string, teamId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeam
}

export async function addTeamMember(
  request: APIRequestContext,
  token: string,
  teamId: number,
  username: string,
  role: Exclude<E2ETeamMember['role'], 'OWNER'> = 'VIEWER',
) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/members`, {
    headers: authHeaders(token),
    data: { username, role },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeamMember
}

export async function listTeamMembers(request: APIRequestContext, token: string, teamId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/members`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeamMember[]
}

export async function createTeamInvite(
  request: APIRequestContext,
  token: string,
  teamId: number,
  email: string,
  role: Exclude<E2ETeamMember['role'], 'OWNER'> = 'VIEWER',
) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/invites`, {
    headers: authHeaders(token),
    data: { email, role },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeamInvite
}

export async function listTeamInvites(request: APIRequestContext, token: string, teamId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/invites`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeamInvite[]
}

export async function resendTeamInvite(request: APIRequestContext, token: string, teamId: number, inviteId: number) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/invites/${inviteId}/resend`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2ETeamInvite
}

export async function cancelTeamInvite(request: APIRequestContext, token: string, teamId: number, inviteId: number) {
  const res = await request.delete(`${backendURL}/api/v1/teams/${teamId}/invites/${inviteId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
}

export async function createTeamFolder(
  request: APIRequestContext,
  token: string,
  teamId: number,
  folderName = uniqueName('team_folder'),
  parentId: number | null = null,
) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/files/folder`, {
    headers: authHeaders(token),
    data: { parentId, folderName },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EFile
}

export async function uploadTeamSampleFile(
  request: APIRequestContext,
  token: string,
  teamId: number,
  parentId: number | null,
  fileName = uniqueName('team_sample') + '.txt',
  content?: Buffer | string,
) {
  return uploadToApi(request, token, `/teams/${teamId}/upload`, parentId, fileName, content)
}

export async function listTeamFiles(
  request: APIRequestContext,
  token: string,
  teamId: number,
  parentId: number | null = null,
) {
  const params: Record<string, string> = { page: '1', size: '100' }
  if (parentId !== null) params.parentId = String(parentId)
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/files`, {
    headers: authHeaders(token),
    params,
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2EFile[]
}

export async function getTeamFile(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/files/${fileId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EFile
}

export async function getTeamOfficeConfig(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/office/files/${fileId}/config`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as { documentServerUrl: string; editable: boolean; config: Record<string, unknown> }
}

export async function listTeamVersions(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/files/${fileId}/versions`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EFileVersion[]
}

export async function downloadTeamFileBytes(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/download/${fileId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  return res.body()
}

export async function moveTeamFilesToRecycleBin(
  request: APIRequestContext,
  token: string,
  teamId: number,
  fileIds: number[],
) {
  const res = await request.delete(`${backendURL}/api/v1/teams/${teamId}/files`, {
    headers: authHeaders(token),
    data: { fileIds },
  })
  await expect(res, await res.text()).toBeOK()
}

export async function listTeamRecycleBin(request: APIRequestContext, token: string, teamId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/recycle-bin`, {
    headers: authHeaders(token),
    params: { page: '1', size: '100' },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2EFile[]
}

export async function restoreTeamFile(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/recycle-bin/${fileId}/restore`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
}

export async function createTeamShare(
  request: APIRequestContext,
  token: string,
  teamId: number,
  fileId: number,
  options: { password?: string; maxVisits?: number; expireAt?: number; notifyEmail?: string } = {},
) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/shares`, {
    headers: authHeaders(token),
    data: { fileId, ...options },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as E2EShare
}

export async function listTeamShares(request: APIRequestContext, token: string, teamId: number) {
  const res = await request.get(`${backendURL}/api/v1/teams/${teamId}/shares`, {
    headers: authHeaders(token),
    params: { page: '1', size: '100' },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2EShare[]
}

export async function cancelTeamShare(request: APIRequestContext, token: string, teamId: number, shareId: number) {
  const res = await request.delete(`${backendURL}/api/v1/teams/${teamId}/shares/${shareId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
}

export async function createTeamVersionRows(fileId: number, userId: number, versions = [1, 2]) {
  await createVersionRows(fileId, userId, versions)
}

export async function createTag(request: APIRequestContext, token: string, tagName = uniqueName('tag'), color = '#60a5fa') {
  const res = await request.post(`${backendURL}/api/v1/tags`, {
    headers: authHeaders(token),
    data: { tagName, color },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as { id: number; tagName: string; color: string | null; fileCount: number }
}

export async function tagFile(request: APIRequestContext, token: string, tagId: number, fileId: number) {
  const res = await request.post(`${backendURL}/api/v1/tags/${tagId}/files`, {
    headers: authHeaders(token),
    data: { fileIds: [fileId] },
  })
  await expect(res, await res.text()).toBeOK()
}

export async function createTeamTag(
  request: APIRequestContext,
  token: string,
  teamId: number,
  tagName = uniqueName('team_tag'),
  color = '#00d4aa',
) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/tags`, {
    headers: authHeaders(token),
    data: { tagName, color },
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data as { id: number; tagName: string; color: string | null; fileCount: number }
}

export async function tagTeamFile(request: APIRequestContext, token: string, teamId: number, tagId: number, fileId: number) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/tags/${tagId}/files`, {
    headers: authHeaders(token),
    data: { fileIds: [fileId] },
  })
  await expect(res, await res.text()).toBeOK()
}

export async function addTeamFavorite(request: APIRequestContext, token: string, teamId: number, fileId: number) {
  const res = await request.post(`${backendURL}/api/v1/teams/${teamId}/favorites/${fileId}`, {
    headers: authHeaders(token),
  })
  await expect(res, await res.text()).toBeOK()
}

export async function listNotifications(
  request: APIRequestContext,
  token: string,
  options: { page?: number; size?: number; unreadOnly?: boolean; type?: string } = {},
) {
  const params: Record<string, string> = {
    page: String(options.page ?? 1),
    size: String(options.size ?? 100),
    unreadOnly: String(options.unreadOnly ?? false),
  }
  if (options.type) params.type = options.type
  const res = await request.get(`${backendURL}/api/v1/notifications`, {
    headers: authHeaders(token),
    params,
  })
  await expect(res, await res.text()).toBeOK()
  const body = await res.json()
  expect(body.code).toBe(200)
  return body.data.records as E2ENotification[]
}

export async function setUserQuotaBytes(request: APIRequestContext, adminToken: string, userId: number, storageQuota: number) {
  const res = await request.patch(`${backendURL}/api/v1/admin/users/${userId}/quota`, {
    headers: authHeaders(adminToken),
    data: { storageQuota },
  })
  await expect(res, await res.text()).toBeOK()
}

export async function moveToRecycleBin(request: APIRequestContext, token: string, fileIds: number[]) {
  const res = await request.delete(`${backendURL}/api/v1/files`, {
    headers: authHeaders(token),
    data: { fileIds },
  })
  await expect(res, await res.text()).toBeOK()
}

export async function emptyRecycleBin(request: APIRequestContext, token: string) {
  await request.delete(`${backendURL}/api/v1/recycle-bin`, { headers: authHeaders(token) })
}

export async function promoteUserToAdmin(username: string) {
  await psql(`UPDATE users SET role = 'ROLE_ADMIN' WHERE username = '${escapeSql(username)}';`)
}

export async function createAdminUser(request: APIRequestContext) {
  const user = await createUser(request)
  await promoteUserToAdmin(user.username)
  return refreshUserLogin(request, user)
}

export async function lowerUserQuota(username: string, bytes: number) {
  await psql(`UPDATE users SET storage_quota = ${bytes} WHERE username = '${escapeSql(username)}';`)
}

export async function cleanupE2eData() {
  await psql(readE2eCleanupSql())
}

export async function createVersionRows(fileId: number, userId: number, versions = [1, 2]) {
  const values = versions.map(version => {
    return `
      SELECT ${fileId}, ${userId}, ${version}, file_size, md5_hash, storage_path, CURRENT_TIMESTAMP
      FROM files
      WHERE id = ${fileId}
    `
  }).join(' UNION ALL ')
  await psql(`
    INSERT INTO file_versions (file_id, user_id, version_number, file_size, md5_hash, storage_path, created_at)
    ${values}
    ON CONFLICT (file_id, version_number) DO NOTHING;
    UPDATE files SET version_count = (SELECT count(*) FROM file_versions WHERE file_id = ${fileId}) WHERE id = ${fileId};
  `)
}

export async function clearMailpit(request: APIRequestContext) {
  await request.delete(`${mailpitURL}/api/v1/messages`).catch(() => null)
}

export async function getLatestMailText(request: APIRequestContext, recipient: string) {
  await expect.poll(async () => {
    const text = await readLatestMailText(request, recipient)
    return text ? 'found' : 'missing'
  }, { timeout: 15_000 }).toBe('found')
  return (await readLatestMailText(request, recipient))!
}

export async function getLatestVerificationCode(request: APIRequestContext, recipient: string) {
  const text = await getLatestMailText(request, recipient)
  const match = text.match(/\b\d{6}\b/)
  expect(match, text).toBeTruthy()
  return match![0]
}

export function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` }
}

async function uploadToApi(
  request: APIRequestContext,
  token: string,
  uploadPath: string,
  parentId: number | null,
  fileName: string,
  content?: Buffer | string,
) {
  const filePath = path.resolve('e2e/fixtures/sample.txt')
  const data = Buffer.isBuffer(content)
    ? content
    : Buffer.from(content ?? fs.readFileSync(filePath).toString())
  const md5Hash = crypto.createHash('md5').update(data).digest('hex')

  const instant = await request.post(`${backendURL}/api/v1${uploadPath}/instant`, {
    headers: authHeaders(token),
    data: { parentId, fileName, fileSize: data.length, md5Hash },
  })
  const instantBody = await instant.json()
  if (instantBody.code === 200) return { fileId: instantBody.data.fileId as number, fileName }
  expect(instantBody.code).toBe(419010)

  const chunkSize = 5 * 1024 * 1024
  const totalChunks = data.length === 0 ? 0 : Math.ceil(data.length / chunkSize)
  const init = await request.post(`${backendURL}/api/v1${uploadPath}/init`, {
    headers: authHeaders(token),
    data: { parentId, fileName, fileSize: data.length, md5Hash, totalChunks },
  })
  await expect(init, await init.text()).toBeOK()
  const sessionId = (await init.json()).data.sessionId as string

  for (let chunkNumber = 0; chunkNumber < totalChunks; chunkNumber += 1) {
    const start = chunkNumber * chunkSize
    const chunkData = data.subarray(start, start + chunkSize)
    const chunk = await request.post(`${backendURL}/api/v1${uploadPath}/${sessionId}/chunk?chunkNumber=${chunkNumber}`, {
      headers: authHeaders(token),
      multipart: {
        file: {
          name: fileName,
          mimeType: 'text/plain',
          buffer: chunkData,
        },
      },
    })
    await expect(chunk, await chunk.text()).toBeOK()
  }

  const complete = await request.post(`${backendURL}/api/v1${uploadPath}/${sessionId}/complete`, {
    headers: authHeaders(token),
  })
  await expect(complete, await complete.text()).toBeOK()
  const completeBody = await complete.json()
  expect(completeBody.code).toBe(200)
  return { fileId: completeBody.data.fileId as number, fileName }
}

async function readLatestMailText(request: APIRequestContext, recipient: string) {
  const list = await request.get(`${mailpitURL}/api/v1/messages`)
  if (!list.ok()) return null
  const body = await list.json()
  const messages = (body.messages ?? body.Messages ?? []) as Array<{ ID?: string; id?: string; To?: Array<{ Address?: string }>; ToHTML?: string }>
  const match = messages.find(message => {
    const addresses = message.To?.map(to => to.Address).filter(Boolean) ?? []
    return addresses.includes(recipient) || JSON.stringify(message).includes(recipient)
  })
  const id = match?.ID ?? match?.id
  if (!id) return null
  const detail = await request.get(`${mailpitURL}/api/v1/message/${id}`)
  if (!detail.ok()) return null
  const detailBody = await detail.json()
  return [
    detailBody.Text,
    detailBody.text,
    detailBody.HTML,
    detailBody.html,
    detailBody.Subject,
    detailBody.subject,
  ].filter(Boolean).join('\n')
}

async function psql(sql: string) {
  const client = await connectPostgres()
  try {
    await client.query(sql)
  } finally {
    client.end()
  }
}

function readE2eCleanupSql() {
  const candidates = [
    path.resolve(process.cwd(), 'scripts/e2e-cleanup.sql'),
    path.resolve(process.cwd(), '../scripts/e2e-cleanup.sql'),
  ]
  const filePath = candidates.find(candidate => fs.existsSync(candidate))
  if (!filePath) {
    throw new Error(`E2E cleanup SQL not found. Checked: ${candidates.join(', ')}`)
  }
  return fs.readFileSync(filePath, 'utf8')
}

function escapeSql(value: string) {
  return value.replaceAll("'", "''")
}

export function createMinimalDocx(text = 'BetterCloudDrive OnlyOffice E2E') {
  return createZip(new Map<string, Buffer>([
    ['[Content_Types].xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`)],
    ['_rels/.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`)],
    ['word/document.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>${escapeXml(text)}</w:t></w:r></w:p>
    <w:sectPr>
      <w:pgSz w:w="12240" w:h="15840"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>`)],
  ]))
}

export function createMinimalXlsx(text = 'BetterCloudDrive OnlyOffice E2E') {
  return createZip(new Map<string, Buffer>([
    ['[Content_Types].xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>`)],
    ['_rels/.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`)],
    ['xl/workbook.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>`)],
    ['xl/_rels/workbook.xml.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>`)],
    ['xl/worksheets/sheet1.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="inlineStr"><is><t>${escapeXml(text)}</t></is></c>
    </row>
  </sheetData>
</worksheet>`)],
  ]))
}

export function createMinimalPptx(text = 'BetterCloudDrive OnlyOffice E2E') {
  return createZip(new Map<string, Buffer>([
    ['[Content_Types].xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
</Types>`)],
    ['_rels/.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>`)],
    ['ppt/presentation.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>`)],
    ['ppt/_rels/presentation.xml.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
</Relationships>`)],
    ['ppt/slides/slide1.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="7315200" cy="914400"/></a:xfrm></p:spPr>
        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${escapeXml(text)}</a:t></a:r></a:p></p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>`)],
    ['ppt/slides/_rels/slide1.xml.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>`)],
    ['ppt/slideLayouts/slideLayout1.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank">
  <p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>`)],
    ['ppt/slideLayouts/_rels/slideLayout1.xml.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>`)],
    ['ppt/slideMasters/slideMaster1.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
</p:sldMaster>`)],
    ['ppt/slideMasters/_rels/slideMaster1.xml.rels', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>`)],
    ['ppt/theme/theme1.xml', Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="BetterCloudDrive">
  <a:themeElements>
    <a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F9FAFB"/></a:lt2><a:accent1><a:srgbClr val="00D4AA"/></a:accent1><a:accent2><a:srgbClr val="2563EB"/></a:accent2><a:accent3><a:srgbClr val="F59E0B"/></a:accent3><a:accent4><a:srgbClr val="EF4444"/></a:accent4><a:accent5><a:srgbClr val="8B5CF6"/></a:accent5><a:accent6><a:srgbClr val="14B8A6"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink></a:clrScheme>
    <a:fontScheme name="Office"><a:majorFont><a:latin typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Arial"/></a:minorFont></a:fontScheme>
    <a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle/></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
  </a:themeElements>
</a:theme>`)],
  ]))
}

export function zipContainsText(buffer: Buffer, text: string) {
  for (const entry of zipEntries(buffer)) {
    const localOffset = entry.localOffset
    if (localOffset + 30 > buffer.length || buffer.readUInt32LE(localOffset) !== 0x04034b50) continue
    const fileNameLength = buffer.readUInt16LE(localOffset + 26)
    const extraLength = buffer.readUInt16LE(localOffset + 28)
    const dataStart = localOffset + 30 + fileNameLength + extraLength
    const dataEnd = dataStart + entry.compressedSize
    if (dataEnd > buffer.length) continue
    const data = buffer.subarray(dataStart, dataEnd)
    try {
      const plain = entry.method === 8 ? zlib.inflateRawSync(data) : entry.method === 0 ? data : null
      if (plain?.toString('utf8').includes(text)) return true
    } catch {
      // Ignore non-text or malformed zip members while scanning office documents.
    }
  }
  return false
}

function zipEntries(buffer: Buffer) {
  const entries: Array<{ method: number; compressedSize: number; localOffset: number }> = []
  let offset = 0
  while (offset + 46 <= buffer.length) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) {
      offset += 1
      continue
    }
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const fileNameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localOffset = buffer.readUInt32LE(offset + 42)
    entries.push({ method, compressedSize, localOffset })
    offset += 46 + fileNameLength + extraLength + commentLength
  }
  if (entries.length) return entries
  return localZipEntries(buffer)
}

function localZipEntries(buffer: Buffer) {
  const entries: Array<{ method: number; compressedSize: number; localOffset: number }> = []
  let offset = 0
  while (offset + 30 <= buffer.length && buffer.readUInt32LE(offset) === 0x04034b50) {
    const localOffset = offset
    const method = buffer.readUInt16LE(localOffset + 8)
    const compressedSize = buffer.readUInt32LE(localOffset + 18)
    const fileNameLength = buffer.readUInt16LE(localOffset + 26)
    const extraLength = buffer.readUInt16LE(localOffset + 28)
    entries.push({ method, compressedSize, localOffset })
    const dataStart = localOffset + 30 + fileNameLength + extraLength
    const dataEnd = dataStart + compressedSize
    offset = dataEnd
  }
  return entries
}

export function writeTempFile(name: string, sizeBytes: number, fill = 'x') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bcd-e2e-'))
  const filePath = path.join(dir, name)
  const data = Buffer.alloc(sizeBytes, fill)
  data.write(name)
  fs.writeFileSync(filePath, data)
  return filePath
}

function createZip(files: Map<string, Buffer>) {
  const localParts: Buffer[] = []
  const centralParts: Buffer[] = []
  let offset = 0
  for (const [name, data] of files.entries()) {
    const nameBuffer = Buffer.from(name)
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0, 6)
    local.writeUInt16LE(0, 8)
    local.writeUInt16LE(0, 10)
    local.writeUInt16LE(0x21, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuffer.length, 26)
    local.writeUInt16LE(0, 28)
    localParts.push(local, nameBuffer, data)

    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0, 8)
    central.writeUInt16LE(0, 10)
    central.writeUInt16LE(0, 12)
    central.writeUInt16LE(0x21, 14)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBuffer.length, 28)
    central.writeUInt16LE(0, 30)
    central.writeUInt16LE(0, 32)
    central.writeUInt16LE(0, 34)
    central.writeUInt16LE(0, 36)
    central.writeUInt32LE(0, 38)
    central.writeUInt32LE(offset, 42)
    centralParts.push(central, nameBuffer)
    offset += local.length + nameBuffer.length + data.length
  }
  const centralDirectory = Buffer.concat(centralParts)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(0, 4)
  end.writeUInt16LE(0, 6)
  end.writeUInt16LE(files.size, 8)
  end.writeUInt16LE(files.size, 10)
  end.writeUInt32LE(centralDirectory.length, 12)
  end.writeUInt32LE(offset, 16)
  end.writeUInt16LE(0, 20)
  return Buffer.concat([...localParts, centralDirectory, end])
}

function crc32(data: Buffer) {
  let crc = 0xffffffff
  for (const byte of data) {
    crc ^= byte
    for (let i = 0; i < 8; i += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

function escapeXml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
}

async function connectPostgres() {
  const socket = net.createConnection({ host: postgresHost, port: postgresPort })
  const reader = new PgReader(socket)
  await new Promise<void>((resolve, reject) => {
    socket.once('connect', resolve)
    socket.once('error', reject)
  })

  socket.write(startupMessage({
    user: postgresUser,
    database: postgresDatabase,
    client_encoding: 'UTF8',
  }))

  let authComplete = false
  let scram: ScramState | null = null
  while (!authComplete) {
    const message = await reader.read()
    if (message.type === 'R') {
      const authType = message.body.readInt32BE(0)
      if (authType === 0) authComplete = true
      else if (authType === 3) socket.write(passwordMessage(postgresPassword))
      else if (authType === 5) socket.write(passwordMessage(md5Password(message.body.subarray(4, 8))))
      else if (authType === 10) {
        scram = createScramState()
        socket.write(saslInitialResponse(scram.clientFirst))
      } else if (authType === 11 && scram) {
        scram.serverFirst = message.body.subarray(4).toString()
        socket.write(saslResponse(createScramFinal(scram)))
      } else if (authType === 12 && scram) {
        verifyScramFinal(scram, message.body.subarray(4).toString())
      } else {
        throw new Error(`Unsupported PostgreSQL auth type ${authType}`)
      }
    } else if (message.type === 'E') {
      throw new Error(parsePgError(message.body))
    }
  }

  await drainUntilReady(reader)
  return {
    query: (sql: string) => queryPostgres(socket, reader, sql),
    end: () => socket.end(),
  }
}

async function queryPostgres(socket: net.Socket, reader: PgReader, sql: string) {
  socket.write(queryMessage(sql))
  while (true) {
    const message = await reader.read()
    if (message.type === 'E') throw new Error(parsePgError(message.body))
    if (message.type === 'Z') return
  }
}

async function drainUntilReady(reader: PgReader) {
  while (true) {
    const message = await reader.read()
    if (message.type === 'E') throw new Error(parsePgError(message.body))
    if (message.type === 'Z') return
  }
}

class PgReader {
  private buffer = Buffer.alloc(0)
  private waiters: Array<{ resolve: () => void; reject: (error: Error) => void }> = []
  private closedError: Error | null = null

  constructor(socket: net.Socket) {
    socket.on('data', chunk => {
      this.buffer = Buffer.concat([this.buffer, chunk])
      this.waiters.splice(0).forEach(waiter => waiter.resolve())
    })
    socket.on('error', error => {
      this.closedError = error
      this.waiters.splice(0).forEach(waiter => waiter.reject(error))
    })
    socket.on('close', () => {
      this.closedError ??= new Error('PostgreSQL connection closed')
      this.waiters.splice(0).forEach(waiter => waiter.reject(this.closedError!))
    })
  }

  async read() {
    while (this.buffer.length < 5) await this.wait()
    const type = this.buffer.subarray(0, 1).toString()
    const length = this.buffer.readInt32BE(1)
    while (this.buffer.length < 1 + length) await this.wait()
    const body = this.buffer.subarray(5, 1 + length)
    this.buffer = this.buffer.subarray(1 + length)
    return { type, body }
  }

  private wait() {
    if (this.closedError) return Promise.reject(this.closedError)
    return new Promise<void>((resolve, reject) => this.waiters.push({ resolve, reject }))
  }
}

function startupMessage(params: Record<string, string>) {
  const parts = [int32(196608)]
  for (const [key, value] of Object.entries(params)) {
    parts.push(cstring(key), cstring(value))
  }
  parts.push(Buffer.from([0]))
  const body = Buffer.concat(parts)
  return Buffer.concat([int32(body.length + 4), body])
}

function passwordMessage(password: string) {
  return typedMessage('p', cstring(password))
}

function queryMessage(sql: string) {
  return typedMessage('Q', cstring(sql))
}

function saslInitialResponse(clientFirst: string) {
  const mechanism = cstring('SCRAM-SHA-256')
  const response = Buffer.from(clientFirst)
  return typedMessage('p', Buffer.concat([mechanism, int32(response.length), response]))
}

function saslResponse(response: string) {
  return typedMessage('p', Buffer.from(response))
}

function typedMessage(type: string, body: Buffer) {
  return Buffer.concat([Buffer.from(type), int32(body.length + 4), body])
}

function cstring(value: string) {
  return Buffer.from(`${value}\0`)
}

function int32(value: number) {
  const buffer = Buffer.alloc(4)
  buffer.writeInt32BE(value)
  return buffer
}

function md5Password(salt: Buffer) {
  const inner = crypto.createHash('md5').update(postgresPassword + postgresUser).digest('hex')
  return `md5${crypto.createHash('md5').update(Buffer.concat([Buffer.from(inner), salt])).digest('hex')}`
}

interface ScramState {
  nonce: string
  clientFirstBare: string
  clientFirst: string
  serverFirst: string
  serverSignature?: string
}

function createScramState(): ScramState {
  const nonce = crypto.randomBytes(18).toString('base64')
  const clientFirstBare = `n=${saslName(postgresUser)},r=${nonce}`
  return {
    nonce,
    clientFirstBare,
    clientFirst: `n,,${clientFirstBare}`,
    serverFirst: '',
  }
}

function createScramFinal(state: ScramState) {
  const fields = Object.fromEntries(state.serverFirst.split(',').map(part => [part[0], part.slice(2)]))
  const serverNonce = fields.r
  if (!serverNonce?.startsWith(state.nonce)) throw new Error('Invalid SCRAM server nonce')
  const salt = Buffer.from(fields.s, 'base64')
  const iterations = Number(fields.i)
  const clientFinalWithoutProof = `c=biws,r=${serverNonce}`
  const authMessage = `${state.clientFirstBare},${state.serverFirst},${clientFinalWithoutProof}`
  const saltedPassword = crypto.pbkdf2Sync(postgresPassword, salt, iterations, 32, 'sha256')
  const clientKey = hmac(saltedPassword, 'Client Key')
  const storedKey = crypto.createHash('sha256').update(clientKey).digest()
  const clientSignature = hmac(storedKey, authMessage)
  const clientProof = xor(clientKey, clientSignature)
  const serverKey = hmac(saltedPassword, 'Server Key')
  state.serverSignature = hmac(serverKey, authMessage).toString('base64')
  return `${clientFinalWithoutProof},p=${clientProof.toString('base64')}`
}

function verifyScramFinal(state: ScramState, finalMessage: string) {
  const serverSignature = finalMessage.split(',').find(part => part.startsWith('v='))?.slice(2)
  if (state.serverSignature && serverSignature && serverSignature !== state.serverSignature) {
    throw new Error('Invalid SCRAM server signature')
  }
}

function saslName(name: string) {
  return name.replaceAll('=', '=3D').replaceAll(',', '=2C')
}

function hmac(key: Buffer, data: string) {
  return crypto.createHmac('sha256', key).update(data).digest()
}

function xor(left: Buffer, right: Buffer) {
  const result = Buffer.alloc(left.length)
  for (let i = 0; i < left.length; i += 1) result[i] = left[i] ^ right[i]
  return result
}

function parsePgError(body: Buffer) {
  const fields: Record<string, string> = {}
  let start = 0
  while (start < body.length && body[start] !== 0) {
    const code = String.fromCharCode(body[start])
    const end = body.indexOf(0, start + 1)
    fields[code] = body.subarray(start + 1, end).toString()
    start = end + 1
  }
  return fields.M || 'PostgreSQL error'
}
