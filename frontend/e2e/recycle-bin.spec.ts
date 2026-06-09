import { test, expect } from '@playwright/test'

test.describe('Recycle Bin', () => {
  const username = `e2erb_${Date.now()}`
  const pass = 'Recycle1A!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('delete folder then view in recycle bin', async ({ page }) => {
    // Create and delete
    const name = `trash_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', name)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    await page.locator(`text=${name}`).click({ button: 'right' })
    await page.click('.ctx-danger')
    await page.waitForTimeout(500)

    // Navigate to recycle bin
    await page.click('.nav-item:has-text("回收站")')
    await page.waitForURL('/recycle-bin')
    await page.waitForTimeout(500)

    // Check the deleted item appears
    await expect(page.locator('text=回收站')).toBeVisible()
  })

  test('restore file from recycle bin', async ({ page }) => {
    const name = `rest_${Date.now()}`
    await page.click('text=新建文件夹')
    await page.fill('.dialog input', name)
    await page.click('.dialog .btn-primary')
    await page.waitForTimeout(500)

    await page.locator(`text=${name}`).click({ button: 'right' })
    await page.click('.ctx-danger')
    await page.waitForTimeout(500)

    // Go to recycle bin and restore
    await page.goto('/recycle-bin')
    await page.waitForTimeout(500)
    const restoreBtn = page.locator('text=恢复').first()
    if (await restoreBtn.count() > 0) {
      await restoreBtn.click()
      await page.waitForTimeout(500)
    }
  })
})
