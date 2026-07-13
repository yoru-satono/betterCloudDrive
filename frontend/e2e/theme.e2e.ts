import { test, expect } from './fixtures/test'
import { loginViaUi } from './helpers/api'

test('theme follows the system and persists an explicit selection', async ({ page, e2eUser }) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await loginViaUi(page, e2eUser)
  await page.locator('.sidebar__user').click()

  await expect(page.getByTestId('theme-option-system')).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(247, 248, 250)')

  await page.getByTestId('theme-option-dark').click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

  await page.locator('.sidebar__user').click()
  await page.getByTestId('theme-option-system').click()
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
})
