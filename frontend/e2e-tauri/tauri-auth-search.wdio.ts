import {
  authHeaders,
  backendURL,
  clearMailpit,
  createShare,
  createTag,
  createUser,
  getLatestVerificationCode,
  moveToRecycleBin,
  tagFile,
  uniqueName,
  uploadSampleFile,
} from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import {
  clickButton,
  clickElementByClassContaining,
  clickLink,
  closeContextSearch,
  expectContextSearchResult,
  expectDesktopSettings,
  expectSearchTrigger,
  expectVisibleText,
  fillInputByLabel,
  loginViaWindow,
  openContextSearch,
  openSidebarLink,
  resetToLoginWindow,
  waitForTauriWindow,
} from './helpers/wdio-ui'

const password = 'TestPass123!'
const resetPassword = 'ResetPass123!'

describe('Tauri auth and context search', () => {
  it('keeps connection settings available while registering and resetting a password', async () => {
    const request = await createApiRequestContext()
    try {
      const username = `tui${Date.now().toString(36).slice(-8)}`
      const email = `${username}@test.local`

      await clearMailpit(request)
      await resetToLoginWindow()
      await expectDesktopSettings()
      await clickLink('注册账号')
      await expectVisibleText('加入 BetterCloudDrive')
      await expectDesktopSettings()

      await fillInputByLabel('用户名', username)
      await fillInputByLabel('邮箱地址', email)
      await clickButton('发送验证码')
      await expectVisibleText('验证码已发送')
      const registerCode = await getLatestVerificationCode(request, email)
      await fillInputByLabel('邮箱验证码', registerCode)
      await fillInputByLabel('密码', password)
      await fillInputByLabel('确认密码', password)
      await clickButton('注册')
      await expectVisibleText('登录到 BetterCloudDrive')

      await clearMailpit(request)
      await clickLink('忘记密码？')
      await expectVisibleText('找回密码')
      await expectDesktopSettings()
      await fillInputByLabel('邮箱地址', email)
      await clickButton('发送验证码')
      await expectVisibleText('验证码已发送至邮箱')
      const resetCode = await getLatestVerificationCode(request, email)
      await fillInputByLabel('验证码', resetCode)
      await fillInputByLabel('新密码', resetPassword)
      await clickButton('重置密码')

      await waitForTauriWindow()
      await loginViaWindow(username, resetPassword)
    } finally {
      await request.dispose()
    }
  })

  it('searches the active desktop navigation context', async () => {
    const request = await createApiRequestContext()
    try {
      const user = await createUser(request)
      const favoriteFile = await uploadSampleFile(
        request,
        user.accessToken,
        null,
        `${uniqueName('tauri_ctx_favorite')}.txt`,
        'tauri favorite search target',
      )
      const favorite = await request.post(`${backendURL}/api/v1/favorites/${favoriteFile.fileId}`, {
        headers: authHeaders(user.accessToken),
      })
      if (!favorite.ok()) throw new Error(await favorite.text())

      const sharedFile = await uploadSampleFile(
        request,
        user.accessToken,
        null,
        `${uniqueName('tauri_ctx_share')}.txt`,
        'tauri share search target',
      )
      const share = await createShare(request, user.accessToken, sharedFile.fileId)

      const tagName = uniqueName('tauri_ctx_tag')
      const tag = await createTag(request, user.accessToken, tagName)
      const taggedFile = await uploadSampleFile(
        request,
        user.accessToken,
        null,
        `${uniqueName('tauri_ctx_tagged')}.txt`,
        'tauri tagged search target',
      )
      await tagFile(request, user.accessToken, tag.id, taggedFile.fileId)

      const deletedFile = await uploadSampleFile(
        request,
        user.accessToken,
        null,
        `${uniqueName('tauri_ctx_deleted')}.txt`,
        'tauri deleted search target',
      )
      await moveToRecycleBin(request, user.accessToken, [deletedFile.fileId])

      await resetToLoginWindow()
      await expectDesktopSettings()
      await loginViaWindow(user.username, user.password)

      await openSidebarLink('我的收藏')
      await expectSearchTrigger('搜索收藏...')
      await openContextSearch(favoriteFile.fileName)
      await expectContextSearchResult(favoriteFile.fileName)
      await closeContextSearch()

      await openSidebarLink('我的分享')
      await expectSearchTrigger('搜索分享码、文件 ID 或状态...')
      await openContextSearch(share.shareCode)
      await expectContextSearchResult(share.shareCode)
      await closeContextSearch()

      await openSidebarLink('标签管理')
      await expectSearchTrigger('搜索标签名称...')
      await openContextSearch(tagName)
      await expectContextSearchResult(tagName)
      await closeContextSearch()

      await clickElementByClassContaining('tag-item', tagName)
      await expectSearchTrigger(`搜索标签或「${tagName}」下文件...`)
      await openContextSearch(taggedFile.fileName)
      await expectContextSearchResult(taggedFile.fileName)
      await expectContextSearchResult(`标签：${tagName}`)
      await closeContextSearch()

      await openSidebarLink('回收站')
      await expectSearchTrigger('搜索回收站...')
      await openContextSearch(deletedFile.fileName)
      await expectContextSearchResult(deletedFile.fileName)
      await closeContextSearch()
    } finally {
      await request.dispose()
    }
  })
})
