import { test, expect } from '@playwright/test'

test.describe('File Operations', () => {
  const username = `e2efo_${Date.now()}`
  const pass = 'FileOps1A!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('rename a folder', async ({ page }) => {
    // Create folder
    const folderName = `rn_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', folderName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    // Right-click to rename
    const folder = page.locator(`text=${folderName}`)
    await folder.click({ button: 'right' })
    await page.click('.ctx-item:has-text("重命名")')

    // Rename
    const newName = `renamed_${Date.now()}`
    await page.fill('.dialog input', newName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    await expect(page.locator(`text=${newName}`)).toBeVisible({ timeout: 5000 })
  })

  test('delete a file or folder', async ({ page }) => {
    const folderName = `del_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', folderName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    // Right-click delete
    await page.locator(`text=${folderName}`).click({ button: 'right' })
    await page.click('.ctx-danger')

    // Should disappear
    await expect(page.locator(`text=${folderName}`)).not.toBeVisible({ timeout: 5000 })
  })

  test('toggle between list and grid view', async ({ page }) => {
    // Grid view
    await page.click('.toolbar-right .btn-icon:last-child')
    await expect(page.locator('.file-grid')).toBeVisible()
    // List view
    await page.click('.toolbar-right .btn-icon:first-child')
    await expect(page.locator('.file-list')).toBeVisible()
  })

  test('search filters files', async ({ page }) => {
    // Create a file with known name
    const folderName = `srch_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', folderName)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    await page.fill('.search-input', folderName)
    await page.waitForTimeout(600)
    expect(await page.locator('.file-row, .file-card').count()).toBeGreaterThanOrEqual(1)
  })
})
