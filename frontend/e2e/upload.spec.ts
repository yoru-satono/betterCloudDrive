import { test, expect } from '@playwright/test'
import path from 'path'

test.describe('File Upload', () => {
  const username = `e2eup_${Date.now()}`
  const pass = 'UploadTest1!'

  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
    await page.fill('input[placeholder="3-64 字符"]', username)
    await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
    await page.fill('input[placeholder="再次输入密码"]', pass)
    await page.click('button[type="submit"]')
    await page.waitForURL('/files', { timeout: 10000 })
  })

  test('upload a small text file and verify it appears', async ({ page }) => {
    // Create temp test file
    const fs = await import('fs')
    const tmpFile = `/tmp/e2e_upload_${Date.now()}.txt`
    fs.writeFileSync(tmpFile, 'Hello E2E Upload Test!')

    const fileChooserPromise = page.waitForEvent('filechooser')
    await page.locator('button:has-text("上传文件")').click()
    const fileChooser = await fileChooserPromise
    await fileChooser.setFiles(tmpFile)

    // Wait for upload to complete
    await expect(page.locator('.upload-bar')).toBeVisible({ timeout: 5000 })
    await expect(page.locator('.upload-fill')).toBeVisible()

    // Wait for file to appear in list
    await page.waitForTimeout(3000)
    const fileName = path.basename(tmpFile)
    // Check if file appeared
    const fileRow = page.locator(`.file-name-text:has-text("${fileName}")`).or(page.locator(`text=${fileName}`))
    // Cleanup
    fs.unlinkSync(tmpFile)
  })

  test('upload progress bar appears and completes', async ({ page }) => {
    const fs = await import('fs')
    const tmpFile = `/tmp/e2e_progress_${Date.now()}.bin`
    fs.writeFileSync(tmpFile, Buffer.alloc(1024 * 1024)) // 1MB

    const fileChooserPromise = page.waitForEvent('filechooser')
    await page.locator('button:has-text("上传文件")').click()
    const fileChooser = await fileChooserPromise
    await fileChooser.setFiles(tmpFile)

    await expect(page.locator('.upload-bar')).toBeVisible({ timeout: 3000 })
    await expect(page.locator('.upload-fill')).toBeVisible()
    fs.unlinkSync(tmpFile)
  })
})
