# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: shares.spec.ts >> Share Management >> navigate to shares page
- Location: e2e/shares.spec.ts:22:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('text=我的分享').or(locator('text=暂无分享'))
Expected: visible
Error: strict mode violation: locator('text=我的分享').or(locator('text=暂无分享')) resolved to 2 elements:
    1) <h2 data-v-79206f6f="">我的分享</h2> aka getByRole('heading', { name: '我的分享' })
    2) <p data-v-79206f6f="">暂无分享链接</p> aka getByText('暂无分享链接')

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('text=我的分享').or(locator('text=暂无分享'))

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
        - generic [ref=e14]: e2esh_1780992678641
        - generic [ref=e15]: 0 B / 10.00 GB
    - navigation [ref=e17]:
      - link "我的文件" [ref=e18] [cursor=pointer]:
        - /url: /files
        - img [ref=e20]
        - generic [ref=e22]: 我的文件
      - link "回收站" [ref=e23] [cursor=pointer]:
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
          - heading "我的分享" [level=2] [ref=e76]
          - button "创建分享" [ref=e77] [cursor=pointer]
        - paragraph [ref=e79]: 暂无分享链接
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | test.describe('Share Management', () => {
  4  |   const username = `e2esh_${Date.now()}`
  5  |   const pass = 'Share12A!'
  6  | 
  7  |   test.beforeEach(async ({ page }) => {
  8  |     await page.goto('/register')
  9  |     await page.fill('input[placeholder="3-64 字符"]', username)
  10 |     await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', pass)
  11 |     await page.fill('input[placeholder="再次输入密码"]', pass)
  12 |     await page.click('button[type="submit"]')
  13 |     await page.waitForURL('/files', { timeout: 10000 })
  14 |     // Create a test folder to share
  15 |     const name = `shr_${Date.now()}`
  16 |     await page.click('text=新建文件夹')
  17 |     await page.fill('.dialog input', name)
  18 |     await page.click('.dialog .btn-primary')
  19 |     await page.waitForTimeout(500)
  20 |   })
  21 | 
  22 |   test('navigate to shares page', async ({ page }) => {
  23 |     await page.goto('/shares')
  24 |     await page.waitForTimeout(500)
> 25 |     await expect(page.locator('text=我的分享').or(page.locator('text=暂无分享'))).toBeVisible({ timeout: 5000 })
     |                                                                           ^ Error: expect(locator).toBeVisible() failed
  26 |   })
  27 | 
  28 |   test('access invalid share code shows error page', async ({ page }) => {
  29 |     await page.goto('/s/INVALID1')
  30 |     await page.waitForTimeout(1000)
  31 |     // Should show some error state
  32 |     expect(page.url()).toContain('/s/INVALID1')
  33 |   })
  34 | })
  35 | 
```