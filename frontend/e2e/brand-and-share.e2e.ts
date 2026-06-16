import { test, expect } from './fixtures/test'
import { createFolder, createShare, uploadSampleFile } from './helpers/api'

test('shows BetterCloudDrive branding across public and app pages', async ({ page, e2eUser }) => {
  await page.goto('/login')
  await expect(page.getByText('登录到 BetterCloudDrive')).toBeVisible()
  await expect(page.getByText('BetterDrive')).toHaveCount(0)

  await page.goto('/register')
  await expect(page.getByText('加入 BetterCloudDrive')).toBeVisible()
  await expect(page.getByText('BetterDrive')).toHaveCount(0)

  await page.goto('/login')
  await page.getByLabel('用户名').fill(e2eUser.username)
  await page.getByLabel('密码').fill(e2eUser.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/files$/)
  await expect(page.getByText('BetterCloudDrive')).toBeVisible()
})

test('public share page is accessible and exposes guarded download/save actions', async ({ page, request, e2eUser }) => {
  const folder = await createFolder(request, e2eUser.accessToken)
  const file = await uploadSampleFile(request, e2eUser.accessToken, folder.id)
  const share = await createShare(request, e2eUser.accessToken, file.fileId)

  await page.goto(`/s/${share.shareCode}`)

  await expect(page.getByText('BetterCloudDrive').first()).toBeVisible()
  await expect(page.getByText(file.fileName).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '下载文件' })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存' }).first()).toBeVisible()
})
