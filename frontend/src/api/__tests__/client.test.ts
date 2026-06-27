import axios from 'axios'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { toast } from 'vue-sonner'
import api, { ensureFreshAccessToken } from '@/api/client'

vi.mock('vue-sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

const adapter = vi.fn()
const axiosPost = vi.spyOn(axios, 'post')
const toastError = toast.error as Mock

beforeEach(() => {
  adapter.mockReset()
  axiosPost.mockReset()
  toastError.mockReset()
  api.defaults.adapter = adapter
  localStorage.clear()
  window.history.replaceState({}, '', '/')
})

const apiResponse = (data: unknown) => (config: unknown) => Promise.resolve({
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
  data,
})

const apiHttpError = (status: number, data: unknown = {}) => (config: unknown) => Promise.reject({
  message: 'Request failed',
  config,
  response: {
    status,
    statusText: 'Unauthorized',
    headers: {},
    config,
    data,
  },
})

describe('api client', () => {
  it('rejects backend business errors and shows a toast by default', async () => {
    adapter.mockImplementation(apiResponse({ code: 419001, message: 'storage quota exceeded' }))

    await expect(api.get('/files')).rejects.toMatchObject({
      response: { data: { code: 419001, message: 'storage quota exceeded' } },
    })
    expect(toastError).toHaveBeenCalledWith('storage quota exceeded')
  })

  it('does not show a toast when suppressToast is set', async () => {
    adapter.mockImplementation(apiResponse({ code: 419010, message: 'no matching file found for instant upload' }))

    await expect(api.post('/upload/instant', {}, { suppressToast: true })).rejects.toBeTruthy()
    expect(toastError).not.toHaveBeenCalled()
  })

  it('refreshes token on 401001 and retries the original request', async () => {
    localStorage.setItem('refreshToken', 'old-refresh')

    adapter
      .mockImplementationOnce(apiResponse({ code: 401001, message: 'token expired' }))
      .mockImplementationOnce(apiResponse({ code: 200, message: 'success', data: { ok: true } }))

    axiosPost.mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
      },
    })

    const res = await api.get('/files')

    expect(axiosPost).toHaveBeenCalledWith('/api/v1/auth/refresh', { refreshToken: 'old-refresh' })
    expect(localStorage.getItem('accessToken')).toBe('new-access')
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh')
    expect(res.data.data).toEqual({ ok: true })
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it('uses one refresh request for concurrent 401001 responses', async () => {
    localStorage.setItem('refreshToken', 'old-refresh')

    adapter
      .mockImplementationOnce(apiResponse({ code: 401001, message: 'token expired' }))
      .mockImplementationOnce(apiResponse({ code: 401001, message: 'token expired' }))
      .mockImplementationOnce(apiResponse({ code: 200, message: 'success', data: { id: 1 } }))
      .mockImplementationOnce(apiResponse({ code: 200, message: 'success', data: { id: 2 } }))

    let resolveRefresh: (value: unknown) => void
    axiosPost.mockReturnValue(new Promise(resolve => {
      resolveRefresh = resolve
    }) as ReturnType<typeof axios.post>)

    const req1 = api.get('/files/1')
    const req2 = api.get('/files/2')

    await vi.waitFor(() => expect(axiosPost).toHaveBeenCalledTimes(1))
    expect(axiosPost).toHaveBeenCalledTimes(1)

    resolveRefresh!({
      data: {
        code: 200,
        message: 'success',
        data: { accessToken: 'queued-access', refreshToken: 'queued-refresh' },
      },
    })

    const results = await Promise.all([req1, req2])
    expect(results.map(res => res.data.data)).toEqual(expect.arrayContaining([{ id: 1 }, { id: 2 }]))
    expect(axiosPost).toHaveBeenCalledTimes(1)
  })

  it('refreshes when token validation returns a plain HTTP 401', async () => {
    localStorage.setItem('accessToken', 'expired-access')
    localStorage.setItem('refreshToken', 'old-refresh')
    adapter.mockImplementation(apiHttpError(401))
    axiosPost.mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
      },
    })

    await expect(ensureFreshAccessToken()).resolves.toBe('new-access')

    expect(axiosPost).toHaveBeenCalledWith('/api/v1/auth/refresh', { refreshToken: 'old-refresh' })
    expect(localStorage.getItem('accessToken')).toBe('new-access')
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh')
    expect(toastError).not.toHaveBeenCalled()
  })
})
