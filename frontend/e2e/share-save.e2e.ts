import { test, expect } from './fixtures/test'
import {
  accessShare,
  createAuthenticatedContext,
  createFolder,
  createShare,
  createUser,
  getShare,
  listFiles,
  loginViaUi,
  lowerUserQuota,
  uploadSampleFile,
  uniqueName,
} from './helpers/api'

test.describe('share save, password, and visit limits', () => {
  test('saves shared folder roots and child files into the receiver drive', async ({ browser, page, request, e2eUser }) => {
    const receiver = await createUser(request)
    const sharedFolder = await createFolder(request, e2eUser.accessToken, uniqueName('shared-root'))
    const nestedFolder = await createFolder(request, e2eUser.accessToken, uniqueName('nested'), sharedFolder.id)
    const child = await uploadSampleFile(request, e2eUser.accessToken, nestedFolder.id, uniqueName('child') + '.txt')
    const rootFile = await uploadSampleFile(request, e2eUser.accessToken, sharedFolder.id, uniqueName('root-file') + '.txt')
    const receiverTarget = await createFolder(request, receiver.accessToken, uniqueName('receiver-target'))
    const share = await createShare(request, e2eUser.accessToken, sharedFolder.id)

    const anonymousContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const anonymousPage = await anonymousContext.newPage()
    await anonymousPage.goto(`/s/${share.shareCode}`)
    await expect(anonymousPage.getByText(sharedFolder.fileName)).toBeVisible()
    await anonymousPage.getByRole('button', { name: '保存' }).first().click()
    await expect(anonymousPage).toHaveURL(/\/login$/)
    await anonymousContext.close()

    const receiverContext = await createAuthenticatedContext(browser, receiver)
    const receiverPage = await receiverContext.newPage()
    await receiverPage.goto(`/s/${share.shareCode}`)
    await expect(receiverPage.getByText(sharedFolder.fileName).first()).toBeVisible()

    await receiverPage.getByRole('button', { name: '保存' }).first().click()
    await expect(receiverPage.getByRole('dialog', { name: '保存到我的网盘' })).toBeVisible()
    await receiverPage.getByRole('button', { name: '保存到此处' }).click()
    await expect(receiverPage.getByText('已保存到我的网盘')).toBeVisible()

    let receiverRoot = await listFiles(request, receiver.accessToken)
    const savedRoot = receiverRoot.find(file => file.fileName === sharedFolder.fileName)
    expect(savedRoot).toBeTruthy()
    const savedChildren = await listFiles(request, receiver.accessToken, savedRoot!.id)
    const savedNested = savedChildren.find(file => file.fileName === nestedFolder.fileName)
    expect(savedNested).toBeTruthy()
    expect(savedChildren.some(file => file.fileName === rootFile.fileName)).toBe(true)
    const savedNestedFiles = await listFiles(request, receiver.accessToken, savedNested!.id)
    expect(savedNestedFiles.some(file => file.fileName === child.fileName)).toBe(true)

    await receiverPage.goto(`/s/${share.shareCode}`)
    const childRow = receiverPage.locator('.share-page__file-row').filter({ hasText: rootFile.fileName })
    await childRow.getByRole('button', { name: '保存' }).click()
    await expect(receiverPage.getByRole('dialog', { name: '保存到我的网盘' })).toBeVisible()
    await receiverPage.getByRole('button', { name: receiverTarget.fileName }).click()
    await receiverPage.getByRole('button', { name: '保存到此处' }).click()
    await expect(receiverPage.getByText('已保存到我的网盘')).toBeVisible()

    const receiverTargetFiles = await listFiles(request, receiver.accessToken, receiverTarget.id)
    expect(receiverTargetFiles.some(file => file.fileName === rootFile.fileName)).toBe(true)

    const finalShare = await getShare(request, e2eUser.accessToken, share.id)
    expect(finalShare.downloadCount).toBeGreaterThanOrEqual(2)
    await receiverContext.close()
  })

  test('shows save conflicts and quota failures with readable messages', async ({ browser, page, request, e2eUser }) => {
    const sourceFile = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('conflict') + '.txt')
    const share = await createShare(request, e2eUser.accessToken, sourceFile.fileId)
    const receiver = await createUser(request)
    await uploadSampleFile(request, receiver.accessToken, null, sourceFile.fileName)

    const receiverContext = await createAuthenticatedContext(browser, receiver)
    const receiverPage = await receiverContext.newPage()
    await receiverPage.goto(`/s/${share.shareCode}`)
    await receiverPage.getByRole('button', { name: '保存' }).first().click()
    await receiverPage.getByRole('button', { name: '保存到此处' }).click()
    await expect(receiverPage.getByText('目标文件夹已存在同名项目')).toBeVisible()
    await receiverContext.close()

    const quotaUser = await createUser(request)
    await lowerUserQuota(quotaUser.username, 1)
    const quotaContext = await createAuthenticatedContext(browser, quotaUser)
    const quotaPage = await quotaContext.newPage()
    await quotaPage.goto(`/s/${share.shareCode}`)
    await quotaPage.getByRole('button', { name: '保存' }).first().click()
    await quotaPage.getByRole('button', { name: '保存到此处' }).click()
    await expect(quotaPage.getByText(/Storage quota exceeded|网盘空间不足/)).toBeVisible()
    await quotaContext.close()

    await page.goto('/login')
  })

  test('enforces manual and generated passwords plus visit limits', async ({ page, request, e2eUser }) => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])

    const file = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('passworded') + '.txt')
    const manualPassword = 'abcd'
    const manualShare = await createShare(request, e2eUser.accessToken, file.fileId, { password: manualPassword })

    await page.goto(`/s/${manualShare.shareCode}`)
    await expect(page.getByText('此分享需要密码')).toBeVisible()
    await page.getByLabel('访问密码').fill('wrong')
    await page.getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('此分享需要密码')).toBeVisible()
    await page.getByLabel('访问密码').fill(manualPassword)
    await page.getByRole('button', { name: '确认' }).click()
    await expect(page.getByText(file.fileName).first()).toBeVisible()

    await loginViaUi(page, e2eUser)
    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '分享' }).click()
    const shareDialog = page.getByRole('dialog', { name: '创建分享' })
    await expect(shareDialog).toBeVisible()
    await shareDialog.locator('.share-password-toggle').click()
    await shareDialog.getByRole('textbox', { name: '访问密码' }).fill('abc')
    await page.getByRole('button', { name: '创建并复制链接' }).click()
    await expect(page.getByText('分享密码长度必须为 4-16 位').first()).toBeVisible()
    await shareDialog.getByRole('textbox', { name: '访问密码' }).fill('a'.repeat(17))
    await page.getByRole('button', { name: '创建并复制链接' }).click()
    await expect(page.getByText('分享密码长度必须为 4-16 位').first()).toBeVisible()
    await page.getByRole('button', { name: '自动生成' }).click()
    await page.getByRole('button', { name: '8 位' }).click()
    await page.getByRole('button', { name: '创建并复制链接' }).click()
    await expect(page.getByText('分享链接和密码已复制到剪贴板')).toBeVisible()
    const clipboard = await page.evaluate(() => navigator.clipboard.readText())
    const generatedPassword = clipboard.match(/访问密码：(.+)/)?.[1]?.trim()
    expect(generatedPassword).toHaveLength(8)

    const noPasswordFile = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('plain') + '.txt')
    const noPasswordShare = await createShare(request, e2eUser.accessToken, noPasswordFile.fileId)
    await page.goto(`/s/${noPasswordShare.shareCode}`)
    await expect(page.getByText(noPasswordFile.fileName).first()).toBeVisible()
    await expect(page.getByText('此分享需要密码')).toHaveCount(0)

    const limitFile = await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('limited') + '.txt')
    const limitedShare = await createShare(request, e2eUser.accessToken, limitFile.fileId, { maxVisits: 1 })
    const firstAccess = await accessShare(request, limitedShare.shareCode)
    expect(firstAccess.body.code).toBe(200)
    const secondAccess = await accessShare(request, limitedShare.shareCode)
    expect(secondAccess.body.code).toBe(419005)

    await page.goto('/shares')
    await expect(page.getByRole('heading', { name: '我的分享' })).toBeVisible()
    const shareCard = page.locator('.share-card').filter({ hasText: noPasswordShare.shareCode })
    await shareCard.getByRole('button', { name: '详情' }).click()
    await expect(page.getByRole('dialog', { name: '分享详情' }).getByText('无密码')).toBeVisible()
    await page.keyboard.press('Escape')
    await shareCard.getByRole('button', { name: '编辑' }).click()
    await page.getByLabel('访问次数限制').fill('2')
    await page.getByLabel('新访问密码').fill('newpass')
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('分享已更新')).toBeVisible()
    const updated = await getShare(request, e2eUser.accessToken, noPasswordShare.id)
    expect(updated.maxVisits).toBe(2)
    expect(updated.passwordHash).toBeTruthy()

    const updatedNoPassword = await accessShare(request, noPasswordShare.shareCode)
    expect(updatedNoPassword.body.code).toBe(419003)
    const updatedWithPassword = await accessShare(request, noPasswordShare.shareCode, 'newpass')
    expect(updatedWithPassword.body.code).toBe(200)
  })
})
