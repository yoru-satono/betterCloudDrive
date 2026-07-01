import { test, expect } from './fixtures/test'
import {
  addTeamFavorite,
  addTeamMember,
  createTeam,
  createTeamFolder,
  createTeamTag,
  createTeamVersionRows,
  createUser,
  listTeamFiles,
  listTeamMembers,
  listTeamRecycleBin,
  listTeamShares,
  loginViaUi,
  moveTeamFilesToRecycleBin,
  tagTeamFile,
  uniqueName,
  uploadTeamSampleFile,
} from './helpers/api'

test.describe('teams', () => {
  test('owner manages team files, shares, versions, and recycle bin', async ({ page, request, e2eUser }) => {
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('web_team'))
    const folder = await createTeamFolder(request, e2eUser.accessToken, team.id, uniqueName('team_folder'))
    const nested = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      folder.id,
      `${uniqueName('nested')}.txt`,
      'nested team content',
    )
    const shareFile = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('share')}.txt`,
      'share team content',
    )
    const versionFile = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('version')}.txt`,
      'version team content',
    )
    await createTeamVersionRows(versionFile.fileId, e2eUser.userId, [1, 2])
    const tag = await createTeamTag(request, e2eUser.accessToken, team.id, uniqueName('team_tag'))
    await tagTeamFile(request, e2eUser.accessToken, team.id, tag.id, shareFile.fileId)
    await addTeamFavorite(request, e2eUser.accessToken, team.id, shareFile.fileId)
    const recycleFile = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('recycle')}.txt`,
      'recycle team content',
    )
    await moveTeamFilesToRecycleBin(request, e2eUser.accessToken, team.id, [recycleFile.fileId])

    await loginViaUi(page, e2eUser)
    await page.goto(`/teams/${team.id}/files`)
    await expect(page.getByRole('heading', { name: team.name })).toBeVisible()
    await expect(page.getByTestId(`file-card-${folder.fileName}`)).toBeVisible()
    await expect(page.getByTestId(`file-card-${shareFile.fileName}`)).toBeVisible()

    await page.getByTestId(`file-card-${folder.fileName}`).dblclick()
    await expect(page.getByTestId(`file-card-${nested.fileName}`)).toBeVisible()
    await page.goto(`/teams/${team.id}/files`)

    await page.getByTestId(`file-card-${shareFile.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '分享' }).click()
    const shareDialog = page.getByRole('dialog', { name: '创建团队分享' })
    await expect(shareDialog).toBeVisible()
    await shareDialog.getByLabel('访问次数限制').fill('3')
    await shareDialog.getByRole('button', { name: '创建并复制链接' }).click()
    await expect(page.getByText('团队分享链接已复制到剪贴板')).toBeVisible()

    await expect.poll(async () => (await listTeamShares(request, e2eUser.accessToken, team.id)).length).toBe(1)
    const [share] = await listTeamShares(request, e2eUser.accessToken, team.id)
    expect(share.maxVisits).toBe(3)

    await page.getByTestId(`file-card-${versionFile.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '版本管理' }).click()
    const versionDialog = page.getByRole('dialog', { name: '版本管理' })
    await expect(versionDialog.getByText('版本 2')).toBeVisible()
    const versionOne = versionDialog.locator('.version-row').filter({ hasText: '版本 1' })
    const download = page.waitForEvent('download')
    await versionOne.getByRole('button', { name: '下载' }).click()
    await expect((await download).suggestedFilename()).toContain(versionFile.fileName)
    await versionOne.getByRole('button', { name: '恢复' }).click()
    await page.getByRole('dialog', { name: '恢复版本' }).getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('版本已恢复')).toBeVisible()
    await expect(versionDialog.getByText('版本 3')).toBeVisible()
    await versionDialog.getByRole('button', { name: '关闭' }).click()

    await page.goto(`/teams/${team.id}/settings`)
    await expect(page.getByRole('heading', { name: '团队设置' })).toBeVisible()
    await expect(page.locator('.team-collection__row').filter({ hasText: shareFile.fileName })).toBeVisible()
    const tagRow = page.locator('.team-tags__item').filter({ hasText: tag.tagName })
    await expect(tagRow).toBeVisible()
    await tagRow.getByRole('button', { name: '查看文件' }).click()
    await expect(page.locator('.team-section').filter({ hasText: `标签文件：${tag.tagName}` })).toBeVisible()
    await expect(page.locator('.team-collection__row').filter({ hasText: shareFile.fileName })).toHaveCount(2)
    await expect(page.locator('.team-shares__row').filter({ hasText: share.shareCode })).toBeVisible()
    await expect(page.getByRole('button', { name: '删除团队' })).toBeVisible()

    await page.goto(`/teams/${team.id}/recycle-bin`)
    const binRow = page.locator('.team-bin__row').filter({ hasText: recycleFile.fileName })
    await expect(binRow).toBeVisible()
    await binRow.click()
    await page.getByRole('button', { name: /恢复/ }).click()
    await expect(page.getByText('已恢复')).toBeVisible()
    await expect.poll(async () => {
      const files = await listTeamRecycleBin(request, e2eUser.accessToken, team.id)
      return files.some(file => file.id === recycleFile.fileId)
    }).toBe(false)
    await expect.poll(async () => {
      const files = await listTeamFiles(request, e2eUser.accessToken, team.id)
      return files.some(file => file.id === recycleFile.fileId)
    }).toBe(true)
  })

  test('owner can transfer ownership and then leave the team', async ({ page, request, e2eUser }) => {
    const successor = await createUser(request)
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('web_transfer_team'))
    await addTeamMember(request, e2eUser.accessToken, team.id, successor.username, 'VIEWER')

    await loginViaUi(page, e2eUser)
    await page.goto(`/teams/${team.id}/settings`)
    const successorRow = page.locator('.team-members__row').filter({ hasText: successor.username })
    await expect(successorRow.getByRole('button', { name: '转让' })).toBeVisible()
    await successorRow.getByRole('button', { name: '转让' }).click()
    await page.getByRole('dialog', { name: '转让团队' }).getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('团队所有者已转让')).toBeVisible()

    await expect.poll(async () => {
      const members = await listTeamMembers(request, successor.accessToken, team.id)
      return members.find(member => member.userId === successor.userId)?.role
    }).toBe('OWNER')
    await expect(page.getByRole('button', { name: '退出团队' })).toBeVisible()
    await page.getByRole('button', { name: '退出团队' }).click()
    await page.getByRole('dialog', { name: '退出团队' }).getByRole('button', { name: '确认' }).click()
    await expect(page).toHaveURL(/\/files$/)
  })

  test('viewer can browse team files without write actions', async ({ page, request, e2eUser }) => {
    const viewer = await createUser(request)
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('web_viewer_team'))
    const file = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('viewer')}.txt`,
      'viewer team content',
    )
    await addTeamMember(request, e2eUser.accessToken, team.id, viewer.username, 'VIEWER')

    await loginViaUi(page, viewer)
    await page.goto(`/teams/${team.id}/files`)
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()
    await expect(page.getByRole('button', { name: '上传文件' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '新建文件夹' })).toHaveCount(0)

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    await expect(page.getByRole('menu').getByRole('menuitem', { name: '版本管理' })).toBeVisible()
    await expect(page.getByRole('menu').getByRole('menuitem', { name: '删除' })).toHaveCount(0)
  })
})
