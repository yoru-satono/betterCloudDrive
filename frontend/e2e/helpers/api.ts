import { APIRequestContext, Browser, BrowserContext, expect, Page } from '@playwright/test'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

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
}

export interface E2EShare {
  id: number
  shareCode: string
  fileId: number
  visitCount: number
  downloadCount: number
  maxVisits?: number | null
  passwordHash?: string | null
}

export const backendURL = process.env.E2E_BACKEND_URL || 'http://localhost:8080'
export const mailpitURL = process.env.E2E_MAILPIT_URL || 'http://localhost:8025'
const postgresHost = process.env.E2E_POSTGRES_HOST || '127.0.0.1'
const postgresPort = Number(process.env.E2E_POSTGRES_PORT || '5432')
const postgresUser = process.env.POSTGRES_USER || 'bcd_user'
const postgresPassword = process.env.POSTGRES_PASSWORD || 'bcd_pass_2026'
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
          origin: process.env.E2E_BASE_URL || 'http://127.0.0.1:3000',
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
  const filePath = path.resolve('e2e/fixtures/sample.txt')
  const data = Buffer.isBuffer(content)
    ? content
    : Buffer.from(content ?? fs.readFileSync(filePath).toString())
  const md5Hash = crypto.createHash('md5').update(data).digest('hex')

  const instant = await request.post(`${backendURL}/api/v1/upload/instant`, {
    headers: authHeaders(token),
    data: { parentId, fileName, fileSize: data.length, md5Hash },
  })
  const instantBody = await instant.json()
  if (instantBody.code === 200) return { fileId: instantBody.data.fileId as number, fileName }
  expect(instantBody.code).toBe(419010)

  const init = await request.post(`${backendURL}/api/v1/upload/init`, {
    headers: authHeaders(token),
    data: { parentId, fileName, fileSize: data.length, md5Hash, totalChunks: 1 },
  })
  await expect(init, await init.text()).toBeOK()
  const sessionId = (await init.json()).data.sessionId as string

  const chunk = await request.post(`${backendURL}/api/v1/upload/${sessionId}/chunk?chunkNumber=0`, {
    headers: authHeaders(token),
    multipart: {
      file: {
        name: fileName,
        mimeType: 'text/plain',
        buffer: data,
      },
    },
  })
  await expect(chunk, await chunk.text()).toBeOK()

  const complete = await request.post(`${backendURL}/api/v1/upload/${sessionId}/complete`, {
    headers: authHeaders(token),
  })
  await expect(complete, await complete.text()).toBeOK()
  const completeBody = await complete.json()
  expect(completeBody.code).toBe(200)
  return { fileId: completeBody.data.fileId as number, fileName }
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

export async function createVersionRows(fileId: number, userId: number, versions = [1, 2]) {
  const now = new Date().toISOString()
  const values = versions.map(version => {
    const storagePath = `e2e/version-placeholder/${fileId}-${version}.txt`
    return `(${fileId}, ${userId}, ${version}, ${version * 10}, 'e2e-md5-${version}', '${storagePath}', '${now}')`
  }).join(', ')
  await psql(`
    INSERT INTO file_versions (file_id, user_id, version_number, file_size, md5_hash, storage_path, created_at)
    VALUES ${values}
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

function escapeSql(value: string) {
  return value.replaceAll("'", "''")
}

export function writeTempFile(name: string, sizeBytes: number, fill = 'x') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bcd-e2e-'))
  const filePath = path.join(dir, name)
  const data = Buffer.alloc(sizeBytes, fill)
  data.write(name)
  fs.writeFileSync(filePath, data)
  return filePath
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
