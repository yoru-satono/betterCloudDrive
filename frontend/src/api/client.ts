import axios, { AxiosError, CanceledError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { toast } from 'vue-sonner'
import { i18n, resolveMessage } from '@/i18n'
import { getApiBaseUrl } from '@/config/runtime'
import { identityRevision, invalidateIdentity } from '@/platform/identity'
import { rawInvoke, waitForConnection, isConnectionConfirmed } from '@/platform/desktop'
import { isQtRuntime, redirectToLogin } from '@/platform/environment'

declare module 'axios' {
  export interface AxiosRequestConfig {
    suppressToast?: boolean
    _retry?: boolean
    _identityRevision?: number
  }
}
interface Envelope {
  code: number
  messageKey: string
  messageParams?: Record<string, string | number | boolean | null>
  data?: unknown
}
const current = (revision: number) => {
  if (revision !== identityRevision()) throw new CanceledError('SESSION_CHANGED')
}
const expire = () => {
  invalidateIdentity('expired')
  if (isQtRuntime()) void rawInvoke('session:clear').catch(() => undefined)
  redirectToLogin()
}
const api = axios.create({ timeout: 30000, headers: { 'Content-Type': 'application/json' } })
api.interceptors.request.use(config => {
  const revision = config._identityRevision ?? identityRevision()
  config._identityRevision = revision
  current(revision)
  if (!isConnectionConfirmed()) throw new CanceledError('CONNECTION_NOT_CONFIRMED')
  config.baseURL = getApiBaseUrl()
  const token = localStorage.getItem('accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  else delete config.headers.Authorization
  return config
}, error => { throw error }, { synchronous: true })

let refreshing: { revision: number; promise: Promise<string> } | undefined
export function refreshAccessToken(): Promise<string> {
  const revision = identityRevision()
  if (refreshing?.revision === revision) return refreshing.promise
  const base = getApiBaseUrl()
  const refreshToken = localStorage.getItem('refreshToken')
  const entry = { revision, promise: undefined as unknown as Promise<string> }
  entry.promise = (async () => {
    try {
      await waitForConnection()
      current(revision)
      if (!refreshToken) throw new Error(i18n.global.t('file.client.missingRefreshToken'))
      const { data } = await axios.post<Envelope & { data?: { accessToken: string; refreshToken: string } }>(
        base + '/auth/refresh', { refreshToken }, { timeout: 30000 })
      current(revision)
      if (base !== getApiBaseUrl() || refreshToken !== localStorage.getItem('refreshToken')) throw new CanceledError('SESSION_CHANGED')
      if (data.code !== 200 || !data.data?.accessToken || !data.data.refreshToken) throw new Error(resolveMessage(data.messageKey, data.messageParams))
      localStorage.setItem('accessToken', data.data.accessToken)
      localStorage.setItem('refreshToken', data.data.refreshToken)
      return data.data.accessToken
    } catch (error) {
      current(revision)
      expire()
      throw error
    } finally {
      if (refreshing === entry) refreshing = undefined
    }
  })()
  refreshing = entry
  return entry.promise
}

async function handleError(error: AxiosError<Envelope>) {
  const request = error.config
  if (request?._identityRevision !== undefined) current(request._identityRevision)
  if (axios.isCancel(error)) throw error
  const code = error.response?.data?.code
  const refreshable = code === 401001 || (error.response?.status === 401 && code === undefined)
  if (refreshable && request && !request._retry) {
    request._retry = true
    await refreshAccessToken()
    current(request._identityRevision!)
    return api(request)
  }
  if (code === 401 || code === 401002 || refreshable) expire()
  else if (error.response?.status !== 401 && !request?.suppressToast) {
    const envelope = error.response?.data
    toast.error(envelope?.messageKey ? resolveMessage(envelope.messageKey, envelope.messageParams) : i18n.global.t('file.client.networkConnectionFailedPleaseTryAgainLater'))
  }
  throw error
}
api.interceptors.response.use((response: AxiosResponse<Envelope>) => {
  current(response.config._identityRevision!)
  if (!['blob', 'arraybuffer'].includes(response.config.responseType || '') && response.data && typeof response.data === 'object' && 'code' in response.data && response.data.code !== 200) {
    return handleError(new AxiosError(resolveMessage(response.data.messageKey, response.data.messageParams), undefined, response.config as InternalAxiosRequestConfig, response.request, response))
  }
  return response
}, handleError)
export async function ensureFreshAccessToken() {
  if (!localStorage.getItem('accessToken')) return undefined
  const revision = identityRevision()
  await api.get('/auth/me', { suppressToast: true })
  current(revision)
  return localStorage.getItem('accessToken') || undefined
}
// Revoke only the captured old identity, without touching the next login.
export async function revokeRemote(base: string, token: string | null) {
  if (token) await axios.post(base + '/auth/logout', undefined, { headers: { Authorization: `Bearer ${token}` }, timeout: 10000 })
}
export default api
