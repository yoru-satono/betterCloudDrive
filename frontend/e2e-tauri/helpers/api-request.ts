import { request, type APIRequestContext } from '@playwright/test'

export async function createApiRequestContext(): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080',
  })
}
