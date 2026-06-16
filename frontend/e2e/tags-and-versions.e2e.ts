import { test, expect } from './fixtures/test'
import {
  createTag,
  createVersionRows,
  loginViaUi,
  tagFile,
  uniqueName,
  uploadSampleFile,
} from './helpers/api'

test.describe('tags and versions', () => {
  test('creates, edits, assigns, views, removes, and deletes tags', async ({ page, request, e2eUser }) => {
    const file = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('tagged') + '.txt')
    const tagName = uniqueName('tag')
    const editedTagName = `${tagName}_edited`

    await loginViaUi(page, e2eUser)
    await page.getByRole('link', { name: '标签管理' }).click()
    await expect(page.getByText('标签')).toBeVisible()
    await page.getByRole('button', { name: '+ 新建' }).click()
    await page.getByRole('dialog', { name: '新建标签' }).getByLabel('标签名称').fill(tagName)
    await page.getByRole('dialog', { name: '新建标签' }).getByRole('button', { name: '创建' }).click()
    await expect(page.getByText('标签已创建')).toBeVisible()
    await expect(page.getByText(tagName)).toBeVisible()

    const tagRow = page.locator('.tag-item').filter({ hasText: tagName })
    await tagRow.hover()
    await tagRow.getByRole('button', { name: '编辑' }).click()
    await page.getByRole('dialog', { name: '编辑标签' }).getByLabel('标签名称').fill(editedTagName)
    await page.getByRole('dialog', { name: '编辑标签' }).getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('标签已更新')).toBeVisible()
    await expect(page.getByText(editedTagName)).toBeVisible()

    await page.getByRole('link', { name: '全部文件' }).click()
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()
    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '管理标签' }).click()
    await expect(page.getByRole('dialog', { name: '管理标签' })).toBeVisible()
    await page.getByRole('dialog', { name: '管理标签' }).getByText(editedTagName).click()
    await page.getByRole('dialog', { name: '管理标签' }).getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('标签已更新')).toBeVisible()

    await page.getByRole('link', { name: '标签管理' }).click()
    await page.locator('.tag-item').filter({ hasText: editedTagName }).click()
    await expect(page.getByText(file.fileName)).toBeVisible()
    await page.locator('.tag-file-row').filter({ hasText: file.fileName }).getByRole('button', { name: '移除' }).click()
    await expect(page.getByText('已移除此标签')).toBeVisible()
    await expect(page.getByText(file.fileName)).toHaveCount(0)

    const editedRow = page.locator('.tag-item').filter({ hasText: editedTagName })
    await editedRow.hover()
    await editedRow.locator('.tag-item__del').click()
    await page.getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('标签已删除')).toBeVisible()
    await expect(page.getByText(editedTagName)).toHaveCount(0)
  })

  test('lists and deletes prepared file versions from the version manager', async ({ page, request, e2eUser }) => {
    const file = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('versioned') + '.txt')
    await createVersionRows(file.fileId, e2eUser.userId, [1, 2])

    await loginViaUi(page, e2eUser)
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()
    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '版本管理' }).click()

    const dialog = page.getByRole('dialog', { name: '版本管理' })
    await expect(dialog.getByText('版本 2')).toBeVisible()
    await expect(dialog.getByText('版本 1')).toBeVisible()
    await dialog.locator('.version-row').filter({ hasText: '版本 1' }).getByRole('button', { name: '删除' }).click()
    await page.getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('版本已删除')).toBeVisible()
    await expect(dialog.getByText('版本 1')).toHaveCount(0)
    await expect(dialog.getByText('版本 2')).toBeVisible()
  })

  test('can still display pre-associated tags in the file tag manager', async ({ page, request, e2eUser }) => {
    const file = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('pretagged') + '.txt')
    const tag = await createTag(request, e2eUser.accessToken, uniqueName('pretag'))
    await tagFile(request, e2eUser.accessToken, tag.id, file.fileId)

    await loginViaUi(page, e2eUser)
    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '管理标签' }).click()
    const checkbox = page.getByRole('dialog', { name: '管理标签' }).getByRole('checkbox', { name: tag.tagName })
    await expect(checkbox).toBeChecked()
  })
})
