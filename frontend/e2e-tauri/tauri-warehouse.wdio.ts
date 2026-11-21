import { createAdminUser } from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import { clickLink, expectDesktopSettings, expectVisibleText, loginViaWindow, resetToLoginWindow } from './helpers/wdio-ui'

const warehouseSuite = process.env.E2E_WAREHOUSE === '1' ? describe : describe.skip
warehouseSuite('Tauri business analytics', () => {
  it('opens the shared admin report in a native window', async () => {
    const request = await createApiRequestContext()
    try {
      const admin = await createAdminUser(request)
      await resetToLoginWindow()
      await expectDesktopSettings()
      await loginViaWindow(admin.username, admin.password)
      await clickLink('管理后台')
      await clickLink('业务分析')
      await browser.waitUntil(async () => browser.execute(() =>
        document.querySelector('.analytics')?.getAttribute('aria-busy') === 'false'), {
        timeout: 30_000, timeoutMsg: 'Warehouse report did not finish loading',
      })
      await expectVisibleText('每日趋势')
      await expectVisibleText('加工运行记录')
      const report = await browser.execute(() => ({
        ready: !!document.querySelector('.headline-metrics'),
        alerts: document.querySelectorAll('.analytics [role="alert"]').length,
        layoutFits: document.documentElement.scrollWidth <= window.innerWidth,
      }))
      expect(report.ready).toBe(true)
      expect(report.alerts).toBe(0)
      expect(report.layoutFits).toBe(true)
    } finally {
      await request.dispose()
    }
  })
})
