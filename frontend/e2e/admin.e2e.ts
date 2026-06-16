import { test, expect } from '@playwright/test'

test.describe('admin E2E', () => {
  test.skip(
    !process.env.E2E_ADMIN_USERNAME || !process.env.E2E_ADMIN_PASSWORD,
    'Admin E2E requires E2E_ADMIN_USERNAME and E2E_ADMIN_PASSWORD',
  )

  test('admin can search users and update visible account controls', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('用户名').fill(process.env.E2E_ADMIN_USERNAME!)
    await page.getByLabel('密码').fill(process.env.E2E_ADMIN_PASSWORD!)
    await page.getByRole('button', { name: '登录' }).click()

    await expect(page).toHaveURL(/\/files$/)
    await page.getByRole('link', { name: '管理后台' }).click()
    await expect(page.getByText('系统概览')).toBeVisible()

    await page.getByRole('link', { name: '用户管理' }).click()
    await expect(page.getByText('用户管理')).toBeVisible()
    await page.getByPlaceholder('搜索用户名...').fill(process.env.E2E_ADMIN_USERNAME!)
    await page.getByRole('button', { name: '搜索' }).click()

    await expect(page.getByText(process.env.E2E_ADMIN_USERNAME!)).toBeVisible()
    await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /禁用|启用/ }).first()).toBeVisible()
  })
})
