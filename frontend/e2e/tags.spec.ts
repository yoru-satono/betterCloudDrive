import { test, expect } from '@playwright/test'

test.describe('Tags', () => {
  const username = `e2etg_${Date.now()}`
  const pass = 'TagTest1A!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('navigate to tags page', async ({ page }) => {
    await page.goto('/tags')
    await page.waitForTimeout(500)
    await expect(page.locator('text=标签管理')).toBeVisible({ timeout: 5000 })
  })

  test('create a new tag', async ({ page }) => {
    await page.goto('/tags')
    await page.click('text=新建标签')
    const tagName = `e2e_tag_${Date.now()}`
    await page.fill('.dialog input[placeholder="标签名称"]', tagName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)
    await expect(page.locator(`text=${tagName}`)).toBeVisible({ timeout: 5000 })
  })
})
