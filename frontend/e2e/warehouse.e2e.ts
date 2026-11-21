import { test, expect } from '@playwright/test'
import { authHeaders, backendURL, createAdminUser, loginViaUi } from './helpers/api'

// Auth requests contain credentials; keep them out of browser trace artifacts.
test.use({ trace: 'off', video: 'off' })
test.skip(process.env.E2E_WAREHOUSE !== '1', 'Requires the optional warehouse Compose overlay and a published report')

test('admin analytics matches the published API and remains usable across layouts', async ({ page, request }, testInfo) => {
  const admin = await createAdminUser(request)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await loginViaUi(page, admin)
  const overviewResponse = page.waitForResponse(response => response.url().includes('/admin/warehouse/overview'))
  await page.goto('/admin/analytics')
  const response = await overviewResponse
  expect(response.status()).toBe(200)
  const report = (await response.json()).data
  expect(report.meta.status).toBe('READY')
  await expect(page.getByRole('heading', { name: '业务分析', exact: true })).toBeVisible()
  await expect(page.locator('.analytics')).toHaveAttribute('aria-busy', 'false')
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.locator('.headline-metrics > div').nth(1).locator('strong')).toHaveText(Number(report.usage.active_users).toLocaleString('zh-Hans'))
  const generation = report.meta.generationId
  const direct = await request.get(`${backendURL}/api/v1/admin/warehouse/trends`, {
    headers: authHeaders(admin.accessToken),
    params: { start: report.meta.startDate, end: report.meta.endDate, scope: 'ALL', generationId: generation },
  })
  expect(direct.status()).toBe(200)
  expect((await direct.json()).data.meta.generationId).toBe(generation)

  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => { document.documentElement.dataset.theme = value }, theme)
      await expect(page.getByRole('button', { name: '应用筛选', exact: true })).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath(`warehouse-${width}-${theme}.png`), fullPage: true })
    }
  }
  // Simulate a transient report outage and prove the previous publication survives.
  await page.route('**/api/v1/admin/warehouse/overview?**', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, messageKey: 'error.warehouseUnavailable' }) }))
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('业务分析暂不可用')
  await expect(page.locator('.headline-metrics')).toBeVisible()
  await page.unroute('**/api/v1/admin/warehouse/overview?**')
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.locator('.analytics')).toHaveAttribute('aria-busy', 'false')
  const preference = await request.put(`${backendURL}/api/v1/auth/preferences/locale`, {
    headers: authHeaders(admin.accessToken), data: { preferredLocale: 'en-US' },
  })
  expect(preference.status()).toBe(200)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Business analytics', exact: true })).toBeVisible()
  await expect(page.locator('.analytics')).toHaveAttribute('aria-busy', 'false')
  await expect(page.getByRole('button', { name: 'Apply', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('warehouse-390-english.png'), fullPage: true })
  expect(errors).toEqual([])
})
