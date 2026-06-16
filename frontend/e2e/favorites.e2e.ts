import { test, expect } from './fixtures/test'
import { authHeaders, backendURL, createFolder, uploadSampleFile } from './helpers/api'

test('adds and removes a favorite file', async ({ page, request, e2eUser }) => {
  const folder = await createFolder(request, e2eUser.accessToken)
  const file = await uploadSampleFile(request, e2eUser.accessToken, folder.id)

  await page.goto('/login')
  await page.getByLabel('用户名').fill(e2eUser.username)
  await page.getByLabel('密码').fill(e2eUser.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/files$/)

  await page.getByTestId(`file-card-${folder.fileName}`).dblclick()
  await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()
  await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
  await page.getByRole('menu').getByRole('menuitem', { name: '收藏' }).click()

  await page.getByRole('link', { name: '我的收藏' }).click()
  await expect(page.getByText(file.fileName)).toBeVisible()
  await page.getByRole('button', { name: '取消收藏' }).click()
  await expect(page.getByText(file.fileName)).toHaveCount(0)

  await request.delete(`${backendURL}/api/v1/files`, {
    headers: authHeaders(e2eUser.accessToken),
    data: { fileIds: [folder.id] },
  })
})
