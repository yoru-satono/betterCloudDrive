import { createAdminUser } from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import { clickLink, expectDesktopSettings, expectVisibleText, loginViaWindow, resetToLoginWindow } from './helpers/wdio-ui'

const inspectionSuite = process.env.E2E_INSPECTION === '1' ? describe : describe.skip
inspectionSuite('Tauri storage inspections', () => {
  it('opens the shared inspection console inside the native window', async () => {
    const request = await createApiRequestContext()
    try {
      const admin = await createAdminUser(request)
      await resetToLoginWindow()
      await expectDesktopSettings()
      await loginViaWindow(admin.username, admin.password)
      await clickLink('管理后台')
      await clickLink('存储巡检')
      await expectVisibleText('最近完整巡检')
      await browser.waitUntil(async () => browser.execute(() =>
        !!document.querySelector('.inspection-summary')), { timeout: 30_000, timeoutMsg: 'Inspection summary did not load' })
      const state = await browser.execute(() => ({
        alerts: document.querySelectorAll('.inspections [role="alert"]').length,
        layoutFits: document.documentElement.scrollWidth <= window.innerWidth,
        hasFilters: !!document.querySelector('.inspection-filters'),
        hasHistory: !!document.querySelector('.inspection-history'),
      }))
      expect(state.alerts).toBe(0)
      expect(state.layoutFits).toBe(true)
      expect(state.hasFilters).toBe(true)
      expect(state.hasHistory).toBe(true)
      const scroll = await browser.execute(() => {
        const content = document.querySelector<HTMLElement>('.admin-layout__content')!
        content.scrollTop = content.scrollHeight
        const history = document.querySelector('.inspection-history')!.getBoundingClientRect()
        return { overflow: getComputedStyle(content).overflowY, historyBottom: history.bottom, viewport: window.innerHeight }
      })
      expect(['auto', 'scroll']).toContain(scroll.overflow)
      expect(scroll.historyBottom).toBeLessThanOrEqual(scroll.viewport)
    } finally { await request.dispose() }
  })
})
