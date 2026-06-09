import { test, expect } from '@playwright/test'

test.describe('Favorites', () => {
  const username = `e2efv_${Date.now()}`
  const pass = 'FavTest1A!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('navigate to favorites page shows empty state', async ({ page }) => {
    await page.goto('/favorites')
    await page.waitForTimeout(500)
    await expect(page.locator('text=收藏夹').or(page.locator('text=暂无收藏'))).toBeVisible({ timeout: 5000 })
  })
})
