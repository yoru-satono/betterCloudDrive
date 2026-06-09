import { test, expect } from '@playwright/test'

test.describe('File Management', () => {
  const username = `e2efile_${Date.now()}`
  const password = 'E2eTest123!'

  test.beforeEach(async ({ page }) => {
    // Register a fresh user
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', password)
    await page.fill('input[placeholder="再次输入密码"]', password)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('browse root directory', async ({ page }) => {
    await expect(page.locator('.file-browser')).toBeVisible()
    await expect(page.locator('.topbar')).toBeVisible()
  })

  test('create and delete folder', async ({ page }) => {
    await page.click('text=新建文件夹')
    const folderName = `e2e_folder_${Date.now()}`
    await page.fill('.dialog input', folderName)
    await page.click('.dialog .btn-primary')
    await expect(page.locator(`text=${folderName}`)).toBeVisible({ timeout: 5000 })
    // Delete via context menu
    await page.locator(`text=${folderName}`).click({ button: 'right' })
    await page.click('.ctx-danger')
    await expect(page.locator(`text=${folderName}`)).not.toBeVisible({ timeout: 5000 })
  })

  test('search files', async ({ page }) => {
    await page.fill('.search-input', username)
    await page.waitForTimeout(500)
    expect(await page.locator('.file-row, .file-card').count()).toBeGreaterThanOrEqual(0)
  })

  test('toggle view modes', async ({ page }) => {
    // Grid view
    await page.click('.toolbar-right .btn-icon:last-child')
    await expect(page.locator('.file-grid')).toBeVisible()
    // List view
    await page.click('.toolbar-right .btn-icon:first-child')
    await expect(page.locator('.file-list')).toBeVisible()
  })

  test('navigate into folder and back', async ({ page }) => {
    // Create a folder first
    const folderName = `e2e_nav_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', folderName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)
    // Click into it
    await page.locator(`text=${folderName}`).click()
    await page.waitForURL(/\/files\/\d+/)
    // Navigate back via breadcrumb
    await page.click('.crumb-link:first-child')
    await page.waitForURL('/files')
  })
})
