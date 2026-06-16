import { test, expect } from './fixtures/test'
import {
  createUser,
  loginViaUi,
  uniqueName,
} from './helpers/api'

test('profile no longer exposes post-registration email verification', async ({ page, request }) => {
  const email = `${uniqueName('verify')}@test.local`
  const user = await createUser(request, email)

  await loginViaUi(page, user)
  await page.locator('.sidebar__user').click()
  await expect(page.getByText('邮箱验证')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '发送验证码' })).toHaveCount(0)
  await expect(page.getByText(email)).toBeVisible()
})
