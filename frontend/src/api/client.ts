import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { toast } from 'vue-sonner'

declare module 'axios' {
  export interface AxiosRequestConfig {
    suppressToast?: boolean
    _retry?: boolean
  }
}

interface ApiEnvelope {
  code: number
  message: string
  data?: unknown
}

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let isRefreshing = false
let failedQueue: Array<{ resolve: (v: string) => void; reject: (e: unknown) => void }> = []

const processQueue = (error: unknown, token: string | null) => {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error)
    else resolve(token!)
  })
  failedQueue = []
}

const isApiEnvelope = (data: unknown): data is ApiEnvelope => {
  return !!data && typeof data === 'object' && 'code' in data && 'message' in data
}

const shouldSkipEnvelopeCheck = (res: AxiosResponse) => {
  const responseType = res.config.responseType
  return responseType === 'blob' || responseType === 'arraybuffer'
}

const redirectToLogin = () => {
  localStorage.clear()
  window.location.href = '/login'
}

const handleApiError = async (error: AxiosError<ApiEnvelope>) => {
  const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean } | undefined
  const code = error.response?.data?.code

  if (code === 401001 && originalRequest && !originalRequest._retry) {
    if (isRefreshing) {
      return new Promise<string>((resolve, reject) => {
        failedQueue.push({ resolve, reject })
      }).then((token) => {
        originalRequest.headers.Authorization = `Bearer ${token}`
        return api(originalRequest)
      })
    }

    originalRequest._retry = true
    isRefreshing = true

    const refreshToken = localStorage.getItem('refreshToken')
    if (!refreshToken) {
      isRefreshing = false
      redirectToLogin()
      return Promise.reject(error)
    }

    try {
      const { data } = await axios.post<ApiEnvelope & { data?: { accessToken: string; refreshToken: string } }>('/api/v1/auth/refresh', { refreshToken })
      if (data.code !== 200 || !data.data) throw new Error(data.message || '刷新令牌失败')
      const { accessToken, refreshToken: newRefresh } = data.data
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', newRefresh)
      processQueue(null, accessToken)
      originalRequest.headers.Authorization = `Bearer ${accessToken}`
      return api(originalRequest)
    } catch (e) {
      processQueue(e, null)
      redirectToLogin()
      return Promise.reject(e)
    } finally {
      isRefreshing = false
    }
  }

  const msg = error.response?.data?.message || error.message || '请求失败'
  if (error.response?.status !== 401 && !error.config?.suppressToast) toast.error(msg)
  return Promise.reject(error)
}

api.interceptors.response.use(
  (res) => {
    if (!shouldSkipEnvelopeCheck(res) && isApiEnvelope(res.data) && res.data.code !== 200) {
      const error = new AxiosError(res.data.message || '请求失败', undefined, res.config, res.request, res)
      return handleApiError(error)
    }
    return res
  },
  (error: AxiosError<ApiEnvelope>) => handleApiError(error)
)

export default api
