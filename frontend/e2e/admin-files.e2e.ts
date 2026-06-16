import { test, expect } from '@playwright/test'
import {
  createAdminUser,
  createFolder,
  createUser,
  listFiles,
  loginViaUi,
  uniqueName,
  uploadSampleFile,
} from './helpers/api'

test('admin browses, inspects, and deletes files for a user', async ({ page, request }) => {
  const owner = await createUser(request)
  const admin = await createAdminUser(request)
  const folder = await createFolder(request, owner.accessToken, uniqueName('admin-view'))
  const file = await uploadSampleFile(request, owner.accessToken, folder.id, uniqueName('admin-file') + '.txt')

  await loginViaUi(page, admin)
  await page.getByRole('link', { name: '管理后台' }).click()
  await expect(page.getByText('系统概览')).toBeVisible()
  await page.getByRole('link', { name: '用户管理' }).click()
  await page.getByPlaceholder('搜索用户名...').fill(owner.username)
  await page.getByRole('button', { name: '搜索' }).click()
  const row = page.locator('.users-table__row').filter({ hasText: owner.username })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '文件' }).click()

  await expect(page.getByText(`用户 ${owner.userId} 的文件`)).toBeVisible()
  await page.getByRole('button', { name: folder.fileName }).click()
  await expect(page.getByText(file.fileName)).toBeVisible()

  const fileRow = page.locator('.admin-file-row').filter({ hasText: file.fileName })
  await fileRow.getByRole('button', { name: '详情' }).click()
  const dialog = page.getByRole('dialog', { name: '文件详情' })
  await expect(dialog.getByText(file.fileName)).toBeVisible()
  await expect(dialog.getByText('MD5')).toBeVisible()
  await page.keyboard.press('Escape')

  await fileRow.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认' }).click()
  await expect(page.getByText('文件已删除')).toBeVisible()
  await expect(page.getByText(file.fileName)).toHaveCount(0)

  const remaining = await listFiles(request, owner.accessToken, folder.id)
  expect(remaining.some(item => item.fileName === file.fileName)).toBe(false)
})
