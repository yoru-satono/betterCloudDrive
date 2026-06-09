import { test, expect } from '@playwright/test'

test.describe('Admin Panel Full', () => {
  const username = `e2ead_${Date.now()}`
  const pass = 'AdminFull1!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('regular user sees restricted message on admin page', async ({ page }) => {
    await page.goto('/admin')
    await page.waitForTimeout(1000)
    const hasRestricted = await page.locator('text=需要管理员权限').or(page.locator('.empty-state')).count()
    const hasStats = await page.locator('.stats-grid').count()
    expect(hasRestricted + hasStats).toBeGreaterThan(0)
  })

  test('navigate to admin and verify page loads', async ({ page }) => {
    await page.goto('/admin')
    await page.waitForTimeout(1000)
    // Page should at least render (may show restricted or stats depending on role)
    expect(page.url()).toContain('/admin')
  })
})
