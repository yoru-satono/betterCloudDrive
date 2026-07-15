import { test, expect } from '@playwright/test'
import { createAdminUser, createTeam, loginViaUi, uniqueName } from './helpers/api'

test.describe('admin E2E', () => {
  test('admin can search users and update visible account controls', async ({ page, request }) => {
    const admin = await createAdminUser(request)
    const team = await createTeam(request, admin.accessToken, uniqueName('admin_team'))
    await loginViaUi(page, admin)
    await page.getByRole('link', { name: '管理后台' }).click()
    await expect(page.getByText('系统概览')).toBeVisible()

    await page.getByRole('link', { name: '用户管理' }).click()
    await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible()
    await page.getByPlaceholder('搜索用户名...').fill(admin.username)
    await page.getByRole('button', { name: '搜索' }).click()

    await expect(page.getByText(admin.username, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /禁用|启用/ }).first()).toBeVisible()

    await page.getByRole('link', { name: '团队管理' }).click()
    await expect(page.getByRole('heading', { name: '团队管理' })).toBeVisible()
    await page.getByPlaceholder('搜索团队...').fill(team.name)
    await page.getByRole('button', { name: '搜索' }).click()
    const teamRow = page.locator('.teams-table__row').filter({ hasText: team.name })
    await expect(teamRow).toBeVisible()
    await teamRow.getByRole('button', { name: '详情' }).click()
    await expect(page.getByRole('dialog', { name: `团队详情：${team.name}` })).toBeVisible()
    await expect(page.getByText(`ID ${team.id}`)).toBeVisible()
    await page.getByRole('button', { name: '文件' }).click()
    await expect(page.getByText(/共\s*\d+\s*个文件/)).toBeVisible()
    await page.getByRole('button', { name: '审计' }).click()
    await expect(page.getByPlaceholder('按动作过滤，如 FILE_DELETE')).toBeVisible()
  })
})
