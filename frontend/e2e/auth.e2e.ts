import { test, expect } from './fixtures/test'
import { clearMailpit, getLatestVerificationCode } from './helpers/api'

test('redirects unauthenticated users to login', async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()

  await page.goto('/files')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('登录到 BetterCloudDrive')).toBeVisible()

  await context.close()
})

test('registers with email code and logs in through the UI', async ({ page, request, e2eUser }) => {
  const username = `ui${Date.now().toString(36).slice(-8)}`
  const email = `${username}@test.local`
  await clearMailpit(request)

  await page.goto('/register')
  await expect(page.getByText('加入 BetterCloudDrive')).toBeVisible()

  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('邮箱地址').fill(email)
  await page.getByRole('button', { name: '发送验证码' }).click()
  await expect(page.getByText('验证码已发送')).toBeVisible()
  const code = await getLatestVerificationCode(request, email)
  await page.getByLabel('邮箱验证码').fill(code)
  await page.getByLabel('密码', { exact: true }).fill(e2eUser.password)
  await page.getByLabel('确认密码').fill(e2eUser.password)
  await page.getByRole('button', { name: '注册' }).click()

  await expect(page).toHaveURL(/\/login$/)

  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(e2eUser.password)
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/files$/)
  await expect(page.getByText('BetterCloudDrive')).toBeVisible()

  await page.reload()
  await expect(page.getByRole('link', { name: '全部文件' })).toBeVisible()
})
