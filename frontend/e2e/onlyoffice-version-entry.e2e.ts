import { test, expect } from './fixtures/test'
import {
  addTeamMember,
  createMinimalDocx,
  createTeam,
  createTeamVersionRows,
  createUser,
  loginViaUi,
  uniqueName,
  uploadTeamSampleFile,
} from './helpers/api'

const onlyOfficeURL = process.env.E2E_ONLYOFFICE_URL || 'http://127.0.0.1:8082'

test.describe('onlyoffice version entry', () => {
  test.describe.configure({ timeout: 180_000 })
  test.skip(process.env.E2E_ONLYOFFICE !== '1', 'OnlyOffice E2E is opt-in with E2E_ONLYOFFICE=1')

  test('allows writers to download and restore versions from the editor while viewers can only download', async ({ page, request, e2eUser }) => {
    const api = await request.get(`${onlyOfficeURL}/web-apps/apps/api/documents/api.js`)
    expect(api.ok(), `OnlyOffice Document Server must be reachable at ${onlyOfficeURL}`).toBe(true)

    const viewer = await createUser(request)
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('oo_versions_team'))
    const upload = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('oo_versions')}.docx`,
      createMinimalDocx('OnlyOffice versions baseline'),
    )
    await createTeamVersionRows(upload.fileId, e2eUser.userId, [1, 2])
    await addTeamMember(request, e2eUser.accessToken, team.id, viewer.username, 'VIEWER')

    await loginViaUi(page, e2eUser)
    await page.goto(`/teams/${team.id}/files`)
    await page.getByTestId(`file-card-${upload.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()

    const editorDialog = page.getByRole('dialog', { name: '文件预览' })
    await expect(editorDialog).toBeVisible()
    await expect(editorDialog.getByText('可编辑')).toBeVisible()
    await editorDialog.getByRole('button', { name: '历史版本' }).click()

    const versionDialog = page.getByRole('dialog', { name: '历史版本' })
    await expect(versionDialog).toBeVisible()
    await expect(versionDialog.getByTestId('office-version-row-2')).toBeVisible()
    const versionOne = versionDialog.getByTestId('office-version-row-1')
    const download = page.waitForEvent('download')
    await versionOne.getByRole('button', { name: '下载' }).click()
    await expect((await download).suggestedFilename()).toContain(upload.fileName)

    await versionOne.getByRole('button', { name: '恢复' }).click()
    await page.getByRole('dialog', { name: '恢复版本' }).getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('版本已恢复')).toBeVisible()
    await expect(versionDialog.getByTestId('office-version-row-3')).toBeVisible()
    await versionDialog.getByRole('button', { name: '关闭' }).last().click()
    await editorDialog.getByRole('button', { name: '关闭' }).last().click()

    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })
    await loginViaUi(page, viewer)
    await page.goto(`/teams/${team.id}/files`)
    await page.getByTestId(`file-card-${upload.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()

    const readonlyDialog = page.getByRole('dialog', { name: '文件预览' })
    await expect(readonlyDialog).toBeVisible()
    await expect(readonlyDialog.getByText('只读预览')).toBeVisible()
    await readonlyDialog.getByRole('button', { name: '历史版本' }).click()

    const readonlyVersions = page.getByRole('dialog', { name: '历史版本' })
    await expect(readonlyVersions.getByTestId('office-version-row-1')).toBeVisible()
    await expect(readonlyVersions.getByText('只读权限仅可下载历史版本')).toBeVisible()
    await expect(readonlyVersions.getByRole('button', { name: '恢复' })).toHaveCount(0)
    await expect(readonlyVersions.getByRole('button', { name: '下载' }).first()).toBeVisible()
  })
})
