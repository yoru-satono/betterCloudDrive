import { test, expect } from '@playwright/test'
import { ageInspectionFixture, authHeaders, backendURL, createAdminUser, inspectionQuotaFixture, loginViaUi } from './helpers/api'

test.use({ trace: 'off', video: 'off' })
test.skip(process.env.E2E_INSPECTION !== '1', 'Requires completed offline quota cutover')

test('admin storage inspection starts a real run and retains results across layouts and refresh errors', async ({ page, request }, testInfo) => {
  test.setTimeout(300_000)
  const admin = await createAdminUser(request)
  const headers = authHeaders(admin.accessToken)
  const base = `${backendURL}/api/v1/admin/storage-inspections`
  const overviewResponse = await request.get(base + '/overview', { headers })
  expect(overviewResponse.status()).toBe(200)
  const overview = (await overviewResponse.json()).data
  expect(overview.accountingReady).toBe(true)
  if (overview.latestRun && ['QUEUED', 'RUNNING'].includes(overview.latestRun.status)) {
    await expect.poll(async () => (await (await request.get(base + `/runs/${overview.latestRun.id}`, { headers })).json()).data.status,
      { timeout: 120_000 }).not.toMatch(/^(QUEUED|RUNNING)$/)
  }
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await loginViaUi(page, admin)
  await page.goto('/admin/storage-inspections')
  await expect(page.getByRole('heading', { name: '存储巡检', exact: true })).toBeVisible()
  const start = page.getByRole('button', { name: '开始全量巡检', exact: true })
  await expect(start).toBeEnabled()
  const started = page.waitForResponse(response => response.url().endsWith('/storage-inspections/runs') && response.request().method() === 'POST')
  await start.click()
  const response = await started
  expect(response.status()).toBe(200)
  const run = (await response.json()).data
  await expect.poll(async () => (await (await request.get(base + `/runs/${run.id}`, { headers })).json()).data.status,
    { timeout: 120_000 }).toBe('SUCCEEDED')
  await expect(start).toBeEnabled({ timeout: 15_000 })
  await expect(page.locator('.inspection-summary')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)

  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const theme of ['light', 'dark']) {
      await page.evaluate(value => { document.documentElement.dataset.theme = value }, theme)
      await expect(page.getByRole('button', { name: '应用筛选', exact: true })).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath(`inspection-${width}-${theme}.png`), fullPage: true })
    }
  }
  await page.route('**/api/v1/admin/storage-inspections/overview', route => route.fulfill({ status: 503,
    contentType: 'application/json', body: JSON.stringify({ code: 503, messageKey: 'error.inspectionUnavailable' }) }))
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('刷新失败')
  await expect(page.locator('.inspection-summary')).toBeVisible()
  await page.unroute('**/api/v1/admin/storage-inspections/overview')
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  const preference = await request.put(`${backendURL}/api/v1/auth/preferences/locale`, {
    headers, data: { preferredLocale: 'en-US' },
  })
  expect(preference.status()).toBe(200)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Storage inspections', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start full inspection', exact: true })).toBeEnabled()
  await page.screenshot({ path: testInfo.outputPath('inspection-390-english.png'), fullPage: true })
  const layout = await page.evaluate(() => ({
    width: window.innerWidth, scroll: document.documentElement.scrollWidth,
    overflow: [...document.querySelectorAll('body *')].filter(element => element.getBoundingClientRect().right > window.innerWidth + 1)
      .map(element => ({ tag: element.tagName, class: element.className, right: element.getBoundingClientRect().right })).slice(0, 20),
  }))
  expect(layout.scroll, JSON.stringify(layout)).toBeLessThanOrEqual(layout.width)
  expect(errors).toEqual([])
})

test('admin repairs a controlled quota drift through a fresh preview and explicit confirmation', async ({ page, request }, testInfo) => {
  test.setTimeout(180_000)
  const admin = await createAdminUser(request)
  const headers = authHeaders(admin.accessToken)
  const base = `${backendURL}/api/v1/admin/storage-inspections`
  await inspectionQuotaFixture(admin.userId)
  try {
    await loginViaUi(page, admin)
    await page.goto('/admin/storage-inspections')
    const start = page.getByRole('button', { name: '开始全量巡检', exact: true })
    await expect(start).toBeEnabled()
    const started = page.waitForResponse(response => response.url().endsWith('/storage-inspections/runs') && response.request().method() === 'POST')
    await start.click()
    const run = (await (await started).json()).data
    await expect.poll(async () => (await (await request.get(base + `/runs/${run.id}`, { headers })).json()).data.status,
      { timeout: 120_000 }).toBe('SUCCEEDED')
    await expect(start).toBeEnabled({ timeout: 15_000 })
    await page.getByRole('combobox', { name: '差异类型', exact: true }).selectOption('QUOTA_DRIFT')
    await page.getByRole('combobox', { name: '所属空间', exact: true }).selectOption('PERSONAL')
    await page.getByRole('textbox', { name: '用户 / 团队 ID', exact: true }).fill(String(admin.userId))
    await page.getByRole('button', { name: '应用筛选', exact: true }).click()
    const row = page.locator('.inspection-list tbody tr')
    await expect(row).toHaveCount(1)
    await row.getByRole('button').click()
    await expect(page.locator('.inspection-detail')).toContainText('待复核')
    await expect(page.getByRole('button', { name: '预览用量修复', exact: true })).toHaveCount(0)
    const found = await request.get(base + '/findings', { headers, params: { type: 'QUOTA_DRIFT', scope: 'PERSONAL', ownerId: admin.userId } })
    const finding = (await found.json()).data.records[0]
    // Only this run's evidence is aged; the production minimum remains ten minutes.
    await ageInspectionFixture(finding.id, admin.userId)
    await page.getByRole('button', { name: '再次复核', exact: true }).click()
    const preview = page.getByRole('button', { name: '预览用量修复', exact: true })
    await expect(preview).toBeVisible({ timeout: 15_000 })
    await preview.click()
    const dialog = page.getByRole('dialog', { name: '修复已用容量', exact: true })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.inspection-repair-values')).toContainText('17 B')
    const apply = dialog.getByRole('button', { name: '确认修复', exact: true })
    await expect(apply).toBeDisabled()
    await dialog.getByRole('textbox', { name: '原因或说明', exact: true }).fill('controlled browser acceptance repair')
    await expect(apply).toBeDisabled()
    await dialog.getByRole('checkbox').check()
    await page.setViewportSize({ width: 390, height: 900 })
    await page.screenshot({ path: testInfo.outputPath('inspection-repair-preview-390.png'), fullPage: true })
    await apply.click()
    await expect(dialog).not.toBeVisible()
    await expect(page.locator('.inspection-detail')).toContainText('用量修复已提交')
    await expect(page.locator('.inspection-detail')).toContainText('已解决')
    const detail = await request.get(base + `/findings/${finding.id}`, { headers })
    expect((await detail.json()).data.repairs).toHaveLength(1)
  } finally { await inspectionQuotaFixture(admin.userId, true) }
})
