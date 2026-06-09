# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: admin.spec.ts >> Admin Panel >> non-admin redirects or shows restricted message on admin page
- Location: e2e/admin.spec.ts:23:3

# Error details

```
TimeoutError: page.waitForURL: Timeout 10000ms exceeded.
=========================== logs ===========================
waiting for navigation to "/files" until "load"
============================================================
```

# Page snapshot

```yaml
- generic [ref=e4]:
  - generic [ref=e6]:
    - img [ref=e8]
    - heading "BetterCloudDrive" [level=1] [ref=e12]
    - paragraph [ref=e13]: 安全、快速、自托管的云存储
    - generic [ref=e14]:
      - generic [ref=e15]:
        - img [ref=e16]
        - generic [ref=e18]: 分片上传 + 秒传
      - generic [ref=e19]:
        - img [ref=e20]
        - generic [ref=e22]: 文件分享与协作
      - generic [ref=e23]:
        - img [ref=e24]
        - generic [ref=e26]: 10GB 免费存储
  - generic [ref=e29]:
    - heading "欢迎回来" [level=2] [ref=e30]
    - paragraph [ref=e31]: 登录您的 BetterCloudDrive 账户
    - generic [ref=e32]:
      - generic [ref=e33]:
        - generic [ref=e34]: 用户名
        - textbox "输入用户名" [ref=e35]: e2eadmin_1780992674817
      - generic [ref=e36]:
        - generic [ref=e37]: 密码
        - textbox "输入密码" [ref=e38]: AdminPass123!
      - paragraph [ref=e39]: 登录失败
      - button "登录" [ref=e40] [cursor=pointer]
    - generic [ref=e41]:
      - link "没有账户？注册" [ref=e42] [cursor=pointer]:
        - /url: /register
      - link "忘记密码？" [ref=e43] [cursor=pointer]:
        - /url: /forgot-password
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | test.describe('Admin Panel', () => {
  4  |   const username = `e2eadmin_${Date.now()}`
  5  |   const password = 'AdminPass123!'
  6  | 
  7  |   test.beforeEach(async ({ page }) => {
  8  |     await page.goto('/register')
  9  |     await page.fill('input[placeholder="3-64 字符"]', username)
  10 |     await page.fill('input[placeholder="至少8位，含大小写字母+数字"]', password)
  11 |     await page.fill('input[placeholder="再次输入密码"]', password)
  12 |     await page.click('button[type="submit"]')
  13 |     // If registration fails (user exists), try logging in instead
  14 |     if (page.url().includes('/register')) {
  15 |       await page.goto('/login')
  16 |       await page.fill('input[placeholder="输入用户名"]', username)
  17 |       await page.fill('input[placeholder="输入密码"]', password)
  18 |       await page.click('button[type="submit"]')
  19 |     }
> 20 |     await page.waitForURL('/files', { timeout: 10000 })
     |                ^ TimeoutError: page.waitForURL: Timeout 10000ms exceeded.
  21 |   })
  22 | 
  23 |   test('non-admin redirects or shows restricted message on admin page', async ({ page }) => {
  24 |     await page.goto('/admin')
  25 |     await page.waitForTimeout(1000)
  26 |     // Regular user should see access restriction
  27 |     const restricted = page.locator('text=需要管理员权限').or(page.locator('.empty-state'))
  28 |     const statsGrid = page.locator('.stats-grid')
  29 |     // Either shows restricted message OR (if somehow promoted) shows stats
  30 |     expect(await restricted.count() + await statsGrid.count()).toBeGreaterThan(0)
  31 |   })
  32 | })
  33 | 
```