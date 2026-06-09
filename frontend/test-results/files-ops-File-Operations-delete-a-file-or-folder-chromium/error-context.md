# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: files-ops.spec.ts >> File Operations >> delete a file or folder
- Location: e2e/files-ops.spec.ts:38:3

# Error details

```
Error: expect(locator).not.toBeVisible() failed

Locator:  locator('text=del_1780992680422')
Expected: not visible
Received: visible
Timeout:  5000ms

Call log:
  - Expect "not toBeVisible" with timeout 5000ms
  - waiting for locator('text=del_1780992680422')
    14 × locator resolved to <span data-v-a66bf268="" class="file-name-text truncate">del_1780992680422</span>
       - unexpected value "visible"

```

```yaml
- text: del_1780992680422
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | test.describe('File Operations', () => {
  4  |   const username = `e2efo_${Date.now()}`
  5  |   const pass = 'FileOps1A!'
  6  | 
  7  |   test.beforeEach(async ({ page }) => {
  8  |     await page.goto('/register')
  9  |     await page.fill('input[placeholder="3-64 字符"]', username)
  10 |     await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
  11 |     await page.fill('input[placeholder="再次输入密码"]', pass)
  12 |     await page.click('button[type="submit"]')
  13 |     await page.waitForURL('/files', { timeout: 10000 })
  14 |   })
  15 | 
  16 |   test('rename a folder', async ({ page }) => {
  17 |     // Create folder
  18 |     const folderName = `rn_${Date.now()}`
  19 |     await page.click('text=新建文件夹')
  20 |     await page.fill('.dialog input', folderName)
  21 |     await page.click('.dialog .btn-primary')
  22 |     await page.waitForTimeout(500)
  23 | 
  24 |     // Right-click to rename
  25 |     const folder = page.locator(`text=${folderName}`)
  26 |     await folder.click({ button: 'right' })
  27 |     await page.click('.ctx-item:has-text("重命名")')
  28 | 
  29 |     // Rename
  30 |     const newName = `renamed_${Date.now()}`
  31 |     await page.fill('.dialog input', newName)
  32 |     await page.click('.dialog .btn-primary')
  33 |     await page.waitForTimeout(500)
  34 | 
  35 |     await expect(page.locator(`text=${newName}`)).toBeVisible({ timeout: 5000 })
  36 |   })
  37 | 
  38 |   test('delete a file or folder', async ({ page }) => {
  39 |     const folderName = `del_${Date.now()}`
  40 |     await page.click('text=新建文件夹')
  41 |     await page.fill('.dialog input', folderName)
  42 |     await page.click('.dialog .btn-primary')
  43 |     await page.waitForTimeout(500)
  44 | 
  45 |     // Right-click delete
  46 |     await page.locator(`text=${folderName}`).click({ button: 'right' })
  47 |     await page.click('.ctx-danger')
  48 | 
  49 |     // Should disappear
> 50 |     await expect(page.locator(`text=${folderName}`)).not.toBeVisible({ timeout: 5000 })
     |                                                          ^ Error: expect(locator).not.toBeVisible() failed
  51 |   })
  52 | 
  53 |   test('toggle between list and grid view', async ({ page }) => {
  54 |     // Grid view
  55 |     await page.click('.toolbar-right .btn-icon:last-child')
  56 |     await expect(page.locator('.file-grid')).toBeVisible()
  57 |     // List view
  58 |     await page.click('.toolbar-right .btn-icon:first-child')
  59 |     await expect(page.locator('.file-list')).toBeVisible()
  60 |   })
  61 | 
  62 |   test('search filters files', async ({ page }) => {
  63 |     // Create a file with known name
  64 |     const folderName = `srch_${Date.now()}`
  65 |     await page.click('text=新建文件夹')
  66 |     await page.fill('.dialog input', folderName)
  67 |     await page.click('.dialog .btn-primary')
  68 |     await page.waitForTimeout(500)
  69 | 
  70 |     await page.fill('.search-input', folderName)
  71 |     await page.waitForTimeout(600)
  72 |     expect(await page.locator('.file-row, .file-card').count()).toBeGreaterThanOrEqual(1)
  73 |   })
  74 | })
  75 | 
```