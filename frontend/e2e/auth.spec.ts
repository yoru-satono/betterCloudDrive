import { test, expect } from '@playwright/test'

test.describe('Authentication', () => {
  const testUser = `e2e_${Date.now()}`
  const testPass = 'E2eTest123!'

  test('register and login flow', async ({ page }) => {
    // Register
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', testUser)
    await page.fill('input[placeholder="your@email.com"]', `${testUser}@test.local`)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', testPass)
    await page.fill('input[placeholder="再次输入密码"]', testPass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files')
    // Should see sidebar with username
    await expect(page.locator('.user-name')).toContainText(testUser)
  })

  test('login with wrong password shows error', async ({ page }) => {
    await page.goto('/login')
    await page.fill('input[placeholder="输入用户名"]', 'nonexistent_user')
    await page.fill('input[placeholder="输入密码"]', 'WrongPass1')
    await page.click('button[type="submit"]')
    await expect(page.locator('.error-msg')).toBeVisible()
  })

  test('redirect to login when not authenticated', async ({ page }) => {
    await page.goto('/files')
    await page.waitForURL('/login')
  })

  test('logout clears session', async ({ page }) => {
    // Login first
    await page.goto('/login')
    await page.fill('input[placeholder="输入用户名"]', testUser)
    await page.fill('input[placeholder="输入密码"]', testPass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files')
    // Click logout
    await page.click('.logout-btn')
    await page.waitForURL('/login')
    // Verify redirected
    expect(page.url()).toContain('/login')
  })
})
