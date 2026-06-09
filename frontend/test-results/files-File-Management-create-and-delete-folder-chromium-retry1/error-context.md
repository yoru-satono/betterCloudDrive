# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: files.spec.ts >> File Management >> create and delete folder
- Location: e2e/files.spec.ts:22:3

# Error details

```
Error: expect(locator).not.toBeVisible() failed

Locator:  locator('text=e2e_folder_1780992686369')
Expected: not visible
Received: visible
Timeout:  5000ms

Call log:
  - Expect "not toBeVisible" with timeout 5000ms
  - waiting for locator('text=e2e_folder_1780992686369')
    14 × locator resolved to <span data-v-a66bf268="" class="file-name-text truncate">e2e_folder_1780992686369</span>
       - unexpected value "visible"

```

```yaml
- text: e2e_folder_1780992686369
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | test.describe('File Management', () => {
  4  |   const username = `e2efile_${Date.now()}`
  5  |   const password = 'E2eTest123!'
  6  | 
  7  |   test.beforeEach(async ({ page }) => {
  8  |     // Register a fresh user
  9  |     await page.goto('/register')
  10 |     await page.fill('input[placeholder="3-64 字符"]', username)
  11 |     await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', password)
  12 |     await page.fill('input[placeholder="再次输入密码"]', password)
  13 |     await page.click('button[type="submit"]')
  14 |     await page.waitForURL('/files', { timeout: 10000 })
  15 |   })
  16 | 
  17 |   test('browse root directory', async ({ page }) => {
  18 |     await expect(page.locator('.file-browser')).toBeVisible()
  19 |     await expect(page.locator('.topbar')).toBeVisible()
  20 |   })
  21 | 
  22 |   test('create and delete folder', async ({ page }) => {
  23 |     await page.click('text=新建文件夹')
  24 |     const folderName = `e2e_folder_${Date.now()}`
  25 |     await page.fill('.dialog input', folderName)
  26 |     await page.click('.dialog .btn-primary')
  27 |     await expect(page.locator(`text=${folderName}`)).toBeVisible({ timeout: 5000 })
  28 |     // Delete via context menu
  29 |     await page.locator(`text=${folderName}`).click({ button: 'right' })
  30 |     await page.click('.ctx-danger')
> 31 |     await expect(page.locator(`text=${folderName}`)).not.toBeVisible({ timeout: 5000 })
     |                                                          ^ Error: expect(locator).not.toBeVisible() failed
  32 |   })
  33 | 
  34 |   test('search files', async ({ page }) => {
  35 |     await page.fill('.search-input', username)
  36 |     await page.waitForTimeout(500)
  37 |     expect(await page.locator('.file-row, .file-card').count()).toBeGreaterThanOrEqual(0)
  38 |   })
  39 | 
  40 |   test('toggle view modes', async ({ page }) => {
  41 |     // Grid view
  42 |     await page.click('.toolbar-right .btn-icon:last-child')
  43 |     await expect(page.locator('.file-grid')).toBeVisible()
  44 |     // List view
  45 |     await page.click('.toolbar-right .btn-icon:first-child')
  46 |     await expect(page.locator('.file-list')).toBeVisible()
  47 |   })
  48 | 
  49 |   test('navigate into folder and back', async ({ page }) => {
  50 |     // Create a folder first
  51 |     const folderName = `e2e_nav_${Date.now()}`
  52 |     await page.click('text=新建文件夹')
  53 |     await page.fill('.dialog input', folderName)
  54 |     await page.click('.dialog .btn-primary')
  55 |     await page.waitForTimeout(500)
  56 |     // Click into it
  57 |     await page.locator(`text=${folderName}`).click()
  58 |     await page.waitForURL(/\/files\/\d+/)
  59 |     // Navigate back via breadcrumb
  60 |     await page.click('.crumb-link:first-child')
  61 |     await page.waitForURL('/files')
  62 |   })
  63 | })
  64 | 
```