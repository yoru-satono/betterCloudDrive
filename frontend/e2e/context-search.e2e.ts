import { type Locator, type Page } from '@playwright/test'
import { test, expect } from './fixtures/test'
import {
  authHeaders,
  backendURL,
  createShare,
  createTag,
  loginViaUi,
  moveToRecycleBin,
  tagFile,
  uniqueName,
  uploadSampleFile,
} from './helpers/api'

async function openContextSearch(page: Page, query: string): Promise<Locator> {
  await page.locator('.app-header__search').click()
  const panel = page.locator('.search-panel')
  await expect(panel).toBeVisible()
  await panel.locator('.search-input').fill(query)
  return panel.locator('.search-results')
}

async function closeContextSearch(page: Page) {
  await page.keyboard.press('Escape')
  await expect(page.locator('.search-panel')).toHaveCount(0)
}

test('searches the active navigation context from the shared header search', async ({ page, request, e2eUser }) => {
  const favoriteFile = await uploadSampleFile(
    request,
    e2eUser.accessToken,
    null,
    uniqueName('ctx_favorite') + '.txt',
    'favorite search target',
  )
  const favorite = await request.post(`${backendURL}/api/v1/favorites/${favoriteFile.fileId}`, {
    headers: authHeaders(e2eUser.accessToken),
  })
  await expect(favorite, await favorite.text()).toBeOK()

  const sharedFile = await uploadSampleFile(
    request,
    e2eUser.accessToken,
    null,
    uniqueName('ctx_share') + '.txt',
    'share search target',
  )
  const share = await createShare(request, e2eUser.accessToken, sharedFile.fileId)

  const tagName = uniqueName('ctx_tag')
  const tag = await createTag(request, e2eUser.accessToken, tagName)
  const taggedFile = await uploadSampleFile(
    request,
    e2eUser.accessToken,
    null,
    uniqueName('ctx_tagged') + '.txt',
    'tagged search target',
  )
  await tagFile(request, e2eUser.accessToken, tag.id, taggedFile.fileId)

  const deletedFile = await uploadSampleFile(
    request,
    e2eUser.accessToken,
    null,
    uniqueName('ctx_deleted') + '.txt',
    'deleted search target',
  )
  await moveToRecycleBin(request, e2eUser.accessToken, [deletedFile.fileId])

  await loginViaUi(page, e2eUser)

  await page.getByRole('link', { name: '我的收藏' }).click()
  await expect(page.getByText('搜索收藏...')).toBeVisible()
  let results = await openContextSearch(page, favoriteFile.fileName)
  await expect(results.getByText(favoriteFile.fileName)).toBeVisible()
  await closeContextSearch(page)

  await page.getByRole('link', { name: '我的分享' }).click()
  await expect(page.getByText('搜索分享码、文件 ID 或状态...')).toBeVisible()
  results = await openContextSearch(page, share.shareCode)
  await expect(results.getByText(share.shareCode)).toBeVisible()
  await closeContextSearch(page)

  await page.getByRole('link', { name: '标签管理' }).click()
  await expect(page.getByText('搜索标签名称...')).toBeVisible()
  results = await openContextSearch(page, tagName)
  await expect(results.getByText(tagName)).toBeVisible()
  await closeContextSearch(page)

  await page.locator('.tag-item').filter({ hasText: tagName }).click()
  await expect(page.getByText(`搜索标签或「${tagName}」下文件...`)).toBeVisible()
  await expect(page.locator('.tag-file-row').filter({ hasText: taggedFile.fileName })).toBeVisible()
  results = await openContextSearch(page, taggedFile.fileName)
  await expect(results.getByText(taggedFile.fileName)).toBeVisible()
  await expect(results.getByText(`标签：${tagName}`)).toBeVisible()
  await closeContextSearch(page)

  await page.getByRole('link', { name: '回收站' }).click()
  await expect(page.getByText('搜索回收站...')).toBeVisible()
  results = await openContextSearch(page, deletedFile.fileName)
  await expect(results.getByText(deletedFile.fileName)).toBeVisible()
})
