# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: recycle-bin.spec.ts >> Recycle Bin >> delete folder then view in recycle bin
- Location: e2e/recycle-bin.spec.ts:16:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('text=回收站')
Expected: visible
Error: strict mode violation: locator('text=回收站') resolved to 5 elements:
    1) <span class="nav-label" data-v-22686b16="">回收站</span> aka getByRole('link', { name: '回收站' })
    2) <h2 data-v-f4a32cc0="">回收站</h2> aka getByRole('heading', { name: '回收站' })
    3) <button data-v-f4a32cc0="" class="btn btn-danger btn-sm">清空回收站</button> aka getByRole('button', { name: '清空回收站' })
    4) <p data-v-f4a32cc0="">回收站为空</p> aka getByText('回收站为空')
    5) <span data-v-22686b16="" class="mobile-nav-label">回收站</span> aka getByText('回收站').nth(4)

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('text=回收站')

```

# Page snapshot

```yaml
- generic [ref=e3]:
  - complementary [ref=e4]:
    - generic [ref=e5]:
      - img [ref=e6]
      - generic [ref=e10]: BetterCloudDrive
    - generic [ref=e11]:
      - generic [ref=e12]: E
      - generic [ref=e13]:
        - generic [ref=e14]: e2erb_1780992679609
        - generic [ref=e15]: 0 B / 10.00 GB
    - navigation [ref=e17]:
      - link "我的文件" [ref=e18] [cursor=pointer]:
        - /url: /files
        - img [ref=e20]
        - generic [ref=e22]: 我的文件
      - link "回收站" [active] [ref=e23] [cursor=pointer]:
        - /url: /recycle-bin
        - img [ref=e25]
        - generic [ref=e27]: 回收站
      - link "分享" [ref=e28] [cursor=pointer]:
        - /url: /shares
        - img [ref=e30]
        - generic [ref=e36]: 分享
      - link "收藏" [ref=e37] [cursor=pointer]:
        - /url: /favorites
        - img [ref=e39]
        - generic [ref=e41]: 收藏
      - link "标签" [ref=e42] [cursor=pointer]:
        - /url: /tags
        - img [ref=e44]
        - generic [ref=e46]: 标签
    - generic [ref=e47]:
      - link "个人设置" [ref=e48] [cursor=pointer]:
        - /url: /profile
        - img [ref=e50]
        - generic [ref=e53]: 个人设置
      - button "退出" [ref=e54] [cursor=pointer]:
        - img [ref=e56]
        - generic [ref=e59]: 退出
  - generic [ref=e60]:
    - banner [ref=e61]:
      - generic [ref=e62]:
        - button [ref=e63] [cursor=pointer]:
          - img [ref=e64]
        - generic [ref=e66]: 根目录
      - generic [ref=e68]:
        - img [ref=e69]
        - textbox "搜索文件..." [ref=e72]
    - main [ref=e73]:
      - generic [ref=e74]:
        - generic [ref=e75]:
          - heading "回收站" [level=2] [ref=e76]
          - button "清空回收站" [ref=e77] [cursor=pointer]
        - paragraph [ref=e78]: 文件删除后保留 30 天，之后自动清理
        - generic [ref=e79]:
          - generic [ref=e80]: 名称
          - generic [ref=e81]: 大小
          - generic [ref=e82]: 删除日期
        - paragraph [ref=e85]: 回收站为空
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | test.describe('Recycle Bin', () => {
  4  |   const username = `e2erb_${Date.now()}`
  5  |   const pass = 'Recycle1A!'
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
  16 |   test('delete folder then view in recycle bin', async ({ page }) => {
  17 |     // Create and delete
  18 |     const name = `trash_${Date.now()}`
  19 |     await page.click('text=新建文件夹')
  20 |     await page.fill('.dialog input', name)
  21 |     await page.click('.dialog .btn-primary')
  22 |     await page.waitForTimeout(500)
  23 | 
  24 |     await page.locator(`text=${name}`).click({ button: 'right' })
  25 |     await page.click('.ctx-danger')
  26 |     await page.waitForTimeout(500)
  27 | 
  28 |     // Navigate to recycle bin
  29 |     await page.click('.nav-item:has-text("回收站")')
  30 |     await page.waitForURL('/recycle-bin')
  31 |     await page.waitForTimeout(500)
  32 | 
  33 |     // Check the deleted item appears
> 34 |     await expect(page.locator('text=回收站')).toBeVisible()
     |                                            ^ Error: expect(locator).toBeVisible() failed
  35 |   })
  36 | 
  37 |   test('restore file from recycle bin', async ({ page }) => {
  38 |     const name = `rest_${Date.now()}`
  39 |     await page.click('text=新建文件夹')
  40 |     await page.fill('.dialog input', name)
  41 |     await page.click('.dialog .btn-primary')
  42 |     await page.waitForTimeout(500)
  43 | 
  44 |     await page.locator(`text=${name}`).click({ button: 'right' })
  45 |     await page.click('.ctx-danger')
  46 |     await page.waitForTimeout(500)
  47 | 
  48 |     // Go to recycle bin and restore
  49 |     await page.goto('/recycle-bin')
  50 |     await page.waitForTimeout(500)
  51 |     const restoreBtn = page.locator('text=恢复').first()
  52 |     if (await restoreBtn.count() > 0) {
  53 |       await restoreBtn.click()
  54 |       await page.waitForTimeout(500)
  55 |     }
  56 |   })
  57 | })
  58 | 
```