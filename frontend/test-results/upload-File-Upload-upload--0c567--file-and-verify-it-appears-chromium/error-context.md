# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: upload.spec.ts >> File Upload >> upload a small text file and verify it appears
- Location: e2e/upload.spec.ts:17:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.upload-fill')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('.upload-fill')

```

```yaml
- complementary:
  - img
  - text: BetterCloudDrive E e2eup_1780992503713 0 B / 10.00 GB
  - navigation:
    - link "我的文件":
      - /url: /files
      - img
      - text: 我的文件
    - link "回收站":
      - /url: /recycle-bin
      - img
      - text: 回收站
    - link "分享":
      - /url: /shares
      - img
      - text: 分享
    - link "收藏":
      - /url: /favorites
      - img
      - text: 收藏
    - link "标签":
      - /url: /tags
      - img
      - text: 标签
  - link "个人设置":
    - /url: /profile
    - img
    - text: 个人设置
  - button "退出":
    - img
    - text: 退出
- banner:
  - button:
    - img
  - text: 根目录
  - img
  - textbox "搜索文件..."
- main:
  - button "新建文件夹":
    - img
    - text: 新建文件夹
  - button "上传文件":
    - img
    - text: 上传文件
  - button:
    - img
  - button:
    - img
  - text: 名称 ↑ 大小 修改日期
  - img
  - paragraph: 此目录为空
  - text: 点击上方按钮创建文件夹或上传文件
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | import path from 'path'
  3  | 
  4  | test.describe('File Upload', () => {
  5  |   const username = `e2eup_${Date.now()}`
  6  |   const pass = 'UploadTest1!'
  7  | 
  8  |   test.beforeEach(async ({ page }) => {
  9  |     await page.goto('/register')
  10 |     await page.fill('input[placeholder="3-64 字符"]', username)
  11 |     await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
  12 |     await page.fill('input[placeholder="再次输入密码"]', pass)
  13 |     await page.click('button[type="submit"]')
  14 |     await page.waitForURL('/files', { timeout: 10000 })
  15 |   })
  16 | 
  17 |   test('upload a small text file and verify it appears', async ({ page }) => {
  18 |     // Create temp test file
  19 |     const fs = await import('fs')
  20 |     const tmpFile = `/tmp/e2e_upload_${Date.now()}.txt`
  21 |     fs.writeFileSync(tmpFile, 'Hello E2E Upload Test!')
  22 | 
  23 |     const fileChooserPromise = page.waitForEvent('filechooser')
  24 |     await page.locator('button:has-text("上传文件")').click()
  25 |     const fileChooser = await fileChooserPromise
  26 |     await fileChooser.setFiles(tmpFile)
  27 | 
  28 |     // Wait for upload to complete
  29 |     await expect(page.locator('.upload-bar')).toBeVisible({ timeout: 5000 })
> 30 |     await expect(page.locator('.upload-fill')).toBeVisible()
     |                                                ^ Error: expect(locator).toBeVisible() failed
  31 | 
  32 |     // Wait for file to appear in list
  33 |     await page.waitForTimeout(3000)
  34 |     const fileName = path.basename(tmpFile)
  35 |     // Check if file appeared
  36 |     const fileRow = page.locator(`.file-name-text:has-text("${fileName}")`).or(page.locator(`text=${fileName}`))
  37 |     // Cleanup
  38 |     fs.unlinkSync(tmpFile)
  39 |   })
  40 | 
  41 |   test('upload progress bar appears and completes', async ({ page }) => {
  42 |     const fs = await import('fs')
  43 |     const tmpFile = `/tmp/e2e_progress_${Date.now()}.bin`
  44 |     fs.writeFileSync(tmpFile, Buffer.alloc(1024 * 1024)) // 1MB
  45 | 
  46 |     const fileChooserPromise = page.waitForEvent('filechooser')
  47 |     await page.locator('button:has-text("上传文件")').click()
  48 |     const fileChooser = await fileChooserPromise
  49 |     await fileChooser.setFiles(tmpFile)
  50 | 
  51 |     await expect(page.locator('.upload-bar')).toBeVisible({ timeout: 3000 })
  52 |     await expect(page.locator('.upload-fill')).toBeVisible()
  53 |     fs.unlinkSync(tmpFile)
  54 |   })
  55 | })
  56 | 
```