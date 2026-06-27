import { test, expect } from './fixtures/test'
import {
  createFolder,
  getFile,
  listFiles,
  loginViaUi,
  uniqueName,
  uploadSampleFile,
  writeTempFile,
} from './helpers/api'

test.describe('file actions', () => {
  test('moves and copies files to the root folder through the picker', async ({ page, request, e2eUser }) => {
    const sourceFolder = await createFolder(request, e2eUser.accessToken, uniqueName('root-source'))
    const file = await uploadSampleFile(request, e2eUser.accessToken, sourceFolder.id, uniqueName('root-action') + '.txt', 'root move copy')

    await loginViaUi(page, e2eUser)
    await page.getByTestId(`file-card-${sourceFolder.fileName}`).dblclick()
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '复制到' }).click()
    await expect(page.getByRole('dialog', { name: '复制到' })).toBeVisible()
    await page.getByRole('button', { name: '复制到此处' }).click()
    await expect(page.getByText('已复制')).toBeVisible()

    let rootFiles = await listFiles(request, e2eUser.accessToken)
    expect(rootFiles.some(item => item.fileName === `${file.fileName} (copy)`)).toBe(true)

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '移动到' }).click()
    await expect(page.getByRole('dialog', { name: '移动到' })).toBeVisible()
    await page.getByRole('button', { name: '移动到此处' }).click()
    await expect(page.getByText('已移动')).toBeVisible()

    const sourceFiles = await listFiles(request, e2eUser.accessToken, sourceFolder.id)
    rootFiles = await listFiles(request, e2eUser.accessToken)
    expect(sourceFiles.some(item => item.fileName === file.fileName)).toBe(false)
    expect(rootFiles.some(item => item.fileName === file.fileName)).toBe(true)
    expect(rootFiles.some(item => item.fileName === `${file.fileName} (copy)`)).toBe(true)
  })

  test('opens details and previews, then moves and copies files through the folder picker', async ({ page, request, e2eUser }) => {
    const sourceFolder = await createFolder(request, e2eUser.accessToken, uniqueName('source'))
    const moveTarget = await createFolder(request, e2eUser.accessToken, uniqueName('move-target'))
    const copyTarget = await createFolder(request, e2eUser.accessToken, uniqueName('copy-target'))
    const file = await uploadSampleFile(request, e2eUser.accessToken, sourceFolder.id, uniqueName('action') + '.txt', 'preview text content')

    await loginViaUi(page, e2eUser)
    await page.getByTestId(`file-card-${sourceFolder.fileName}`).dblclick()
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '详情' }).click()
    const detailDialog = page.getByRole('dialog', { name: '文件详情' })
    await expect(detailDialog.getByText(file.fileName)).toBeVisible()
    await expect(detailDialog.getByText('MD5')).toBeVisible()
    await page.keyboard.press('Escape')

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '预览' }).click()
    const previewDialog = page.getByRole('dialog', { name: '文件预览' })
    await expect(previewDialog).toBeVisible()
    await expect(previewDialog.getByText(/此文件暂时无法预览|此类型暂不支持内嵌预览/).or(previewDialog.locator('iframe'))).toBeVisible()
    await previewDialog.getByRole('button', { name: '关闭' }).last().click()

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '复制到' }).click()
    await expect(page.getByRole('dialog', { name: '复制到' })).toBeVisible()
    await page.getByRole('button', { name: copyTarget.fileName }).click()
    await page.getByRole('button', { name: '复制到此处' }).click()
    await expect(page.getByText('已复制')).toBeVisible()

    let copiedFiles = await listFiles(request, e2eUser.accessToken, copyTarget.id)
    expect(copiedFiles.some(item => item.fileName === `${file.fileName} (copy)`)).toBe(true)

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '移动到' }).click()
    await expect(page.getByRole('dialog', { name: '移动到' })).toBeVisible()
    await page.getByRole('button', { name: moveTarget.fileName }).click()
    await page.getByRole('button', { name: '移动到此处' }).click()
    await expect(page.getByText('已移动')).toBeVisible()
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toHaveCount(0)

    const sourceFiles = await listFiles(request, e2eUser.accessToken, sourceFolder.id)
    const movedFiles = await listFiles(request, e2eUser.accessToken, moveTarget.id)
    copiedFiles = await listFiles(request, e2eUser.accessToken, copyTarget.id)
    expect(sourceFiles.some(item => item.fileName === file.fileName)).toBe(false)
    expect(movedFiles.some(item => item.fileName === file.fileName)).toBe(true)
    expect(copiedFiles.some(item => item.fileName === file.fileName)).toBe(false)

    const moved = movedFiles.find(item => item.fileName === file.fileName)!
    const movedDetail = await getFile(request, e2eUser.accessToken, moved.id)
    expect(movedDetail.parentId).toBe(moveTarget.id)
  })

  test('shows upload status controls and can cancel a multipart upload', async ({ page, e2eUser }) => {
    const largeFileName = uniqueName('multipart') + '.bin'
    const largeFilePath = writeTempFile(largeFileName, 6 * 1024 * 1024)

    await loginViaUi(page, e2eUser)
    await page.route('**/api/v1/upload/*/chunk?*', async route => {
      await new Promise(resolve => setTimeout(resolve, 5_000))
      await route.continue()
    })
    await expect(page.getByRole('button', { name: '上传文件' })).toBeVisible()
    await page.getByTestId('file-upload-input').setInputFiles(largeFilePath)

    await expect(page.getByText(largeFileName)).toBeVisible()
    await expect(page.locator('.upload-queue__status').filter({ hasText: /计算中|上传中/ })).toBeVisible()
    const refresh = page.getByRole('button', { name: '刷新状态' })
    const cancel = page.getByRole('button', { name: '取消' }).last()
    await expect(refresh).toBeVisible()
    await refresh.click()
    await cancel.click()
    await expect(page.getByText('已取消')).toBeVisible()
  })

  test('uploads a zero byte file through the browser picker', async ({ page, request, e2eUser }) => {
    const emptyFileName = uniqueName('empty-upload') + '.txt'
    const emptyFilePath = writeTempFile(emptyFileName, 0)

    await loginViaUi(page, e2eUser)
    await expect(page.getByRole('button', { name: '上传文件' })).toBeVisible()
    await page.getByTestId('file-upload-input').setInputFiles(emptyFilePath)

    await expect(page.getByTestId(`file-card-${emptyFileName}`)).toBeVisible()
    await expect.poll(async () => {
      const rootFiles = await listFiles(request, e2eUser.accessToken)
      return rootFiles.find(item => item.fileName === emptyFileName)?.fileSize
    }).toBe(0)
  })
})
