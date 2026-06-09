import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor — inject token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor — handle 401 auto-refresh
let isRefreshing = false
let failedQueue: Array<{ resolve: (v: any) => void; reject: (e: any) => void }> = []

const processQueue = (error: any, token: string | null) => {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error)
    else resolve(token)
  })
  failedQueue = []
}

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const originalRequest = error.config
    if (error.response?.data?.code === 401001 && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
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
        localStorage.clear()
        window.location.href = '/login'
        return Promise.reject(error)
      }

      try {
        const { data } = await axios.post('/api/v1/auth/refresh', { refreshToken })
        const { accessToken, refreshToken: newRefresh } = data.data
        localStorage.setItem('accessToken', accessToken)
        localStorage.setItem('refreshToken', newRefresh)
        processQueue(null, accessToken)
        originalRequest.headers.Authorization = `Bearer ${accessToken}`
        return api(originalRequest)
      } catch (e) {
        processQueue(e, null)
        localStorage.clear()
        window.location.href = '/login'
        return Promise.reject(e)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)

export default api
