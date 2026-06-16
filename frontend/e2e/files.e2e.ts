import path from 'node:path'
import { test, expect } from './fixtures/test'

test('manages a file through upload, rename, download, recycle restore, and permanent delete', async ({ page, e2eUser }) => {
  const folderName = `e2e_folder_${Date.now()}`
  const renamedFolder = `${folderName}_renamed`
  const samplePath = path.resolve('e2e/fixtures/sample.txt')

  await page.goto('/login')
  await page.getByLabel('用户名').fill(e2eUser.username)
  await page.getByLabel('密码').fill(e2eUser.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/files$/)

  await page.getByRole('button', { name: '新建文件夹' }).click()
  await page.getByTestId('new-folder-modal').getByLabel('文件夹名称').fill(folderName)
  await page.getByTestId('new-folder-modal').getByRole('button', { name: '创建' }).click()
  await expect(page.getByTestId(`file-card-${folderName}`)).toBeVisible()

  await page.getByTestId(`file-card-${folderName}`).dblclick()
  await expect(page.getByText(folderName)).toBeVisible()

  const fileChooserPromise = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: '上传文件' }).click()
  const fileChooser = await fileChooserPromise
  await fileChooser.setFiles(samplePath)
  await expect(page.getByTestId('file-card-sample.txt')).toBeVisible()

  const downloadPromise = page.waitForEvent('download')
  await page.getByTestId('file-card-sample.txt').dblclick()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('sample.txt')

  await page.getByRole('button', { name: '全部文件' }).click()
  await expect(page.getByTestId(`file-card-${folderName}`)).toBeVisible()
  await page.getByTestId(`file-card-${folderName}`).click({ button: 'right' })
  await page.getByRole('menu').getByRole('menuitem', { name: '重命名' }).click()
  await page.getByTestId('rename-modal').getByLabel('新名称').fill(renamedFolder)
  await page.getByTestId('rename-modal').getByRole('button', { name: '确认' }).click()
  await expect(page.getByTestId(`file-card-${renamedFolder}`)).toBeVisible()

  await page.getByTestId(`file-card-${renamedFolder}`).click({ button: 'right' })
  await page.getByRole('menu').getByRole('menuitem', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认' }).click()
  await expect(page.getByTestId(`file-card-${renamedFolder}`)).toHaveCount(0)

  await page.getByRole('link', { name: '回收站' }).click()
  await expect(page.getByText(renamedFolder)).toBeVisible()
  await page.getByText(renamedFolder).click()
  await page.getByRole('button', { name: /恢复/ }).click()
  await expect(page.getByText(renamedFolder)).toHaveCount(0)

  await page.getByRole('link', { name: '全部文件' }).click()
  await expect(page.getByTestId(`file-card-${renamedFolder}`)).toBeVisible()
  await page.getByTestId(`file-card-${renamedFolder}`).click({ button: 'right' })
  await page.getByRole('menu').getByRole('menuitem', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认' }).click()

  await page.getByRole('link', { name: '回收站' }).click()
  await expect(page.getByText(renamedFolder)).toBeVisible()
  await page.getByText(renamedFolder).click()
  await page.getByRole('button', { name: /永久删除/ }).click()
  await page.getByRole('button', { name: '确认' }).click()
  await expect(page.getByText(renamedFolder)).toHaveCount(0)
})
