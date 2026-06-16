import path from 'node:path'
import { test, expect } from '@playwright/test'
import { createUser } from './helpers/api'

const authFile = path.resolve('e2e/.auth/user.json')

test('authenticate through the UI', async ({ page, request }) => {
  const user = await createUser(request)

  await page.goto('/login')
  await expect(page.getByText('登录到 BetterCloudDrive')).toBeVisible()
  await page.getByLabel('用户名').fill(user.username)
  await page.getByLabel('密码').fill(user.password)
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/files$/)
  await expect(page.getByText('BetterCloudDrive')).toBeVisible()
  await page.context().storageState({ path: authFile })
})
