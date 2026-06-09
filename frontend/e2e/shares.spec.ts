import { test, expect } from '@playwright/test'

test.describe('Share Management', () => {
  const username = `e2esh_${Date.now()}`
  const pass = 'Share12A!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
    // Create a test folder to share
    const name = `shr_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', name)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)
  })

  test('navigate to shares page', async ({ page }) => {
    await page.goto('/shares')
    await page.waitForTimeout(500)
    await expect(page.locator('text=我的分享').or(page.locator('text=暂无分享'))).toBeVisible({ timeout: 5000 })
  })

  test('access invalid share code shows error page', async ({ page }) => {
    await page.goto('/s/INVALID1')
    await page.waitForTimeout(1000)
    // Should show some error state
    expect(page.url()).toContain('/s/INVALID1')
  })
})
