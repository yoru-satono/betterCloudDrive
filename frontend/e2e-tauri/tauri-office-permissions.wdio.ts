import assert from 'node:assert/strict'
import { browser } from '@wdio/globals'
import {
  addTeamMember,
  createMinimalDocx,
  createTeam,
  createUser,
  uniqueName,
  uploadSampleFile,
  uploadTeamSampleFile,
} from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import {
  closePreviewModal,
  expectContextMenuItemNotVisible,
  expectContextMenuItemVisible,
  expectDesktopSettings,
  expectFileVisible,
  expectOfficePreviewMode,
  expectWriteActionsUnavailable,
  loginViaWindow,
  openFileContextMenu,
  openOfficeFromContextMenu,
  openSidebarLink,
  setAuthTokens,
  waitForTauriWindow,
} from './helpers/wdio-ui'

describe('Tauri OnlyOffice and team permissions', () => {
  it('opens Office previews and hides team write actions for viewers', async () => {
    const request = await createApiRequestContext()
    try {
      const owner = await createUser(request)
      const viewer = await createUser(request)
      const personalDocName = `${uniqueName('tauri_personal_doc')}.docx`
      const teamDocName = `${uniqueName('tauri_team_doc')}.docx`
      const viewerFileName = `${uniqueName('tauri_viewer_file')}.txt`

      await uploadSampleFile(
        request,
        owner.accessToken,
        null,
        personalDocName,
        createMinimalDocx('Tauri personal OnlyOffice preview'),
      )
      const team = await createTeam(request, owner.accessToken, uniqueName('tauri_office_team'))
      await uploadTeamSampleFile(
        request,
        owner.accessToken,
        team.id,
        null,
        teamDocName,
        createMinimalDocx('Tauri team OnlyOffice editor'),
      )
      await uploadTeamSampleFile(request, owner.accessToken, team.id, null, viewerFileName, 'viewer visible')
      await addTeamMember(request, owner.accessToken, team.id, viewer.username, 'VIEWER')

      await resetToLogin()
      await expectDesktopSettings()
      await loginViaWindow(owner.username, owner.password)
      await expectFileVisible(personalDocName)
      await openOfficeFromContextMenu(personalDocName)
      await expectOfficePreviewMode('只读预览')
      await closePreviewModal()

      await openSidebarLink(team.name)
      await expectFileVisible(teamDocName)
      await openOfficeFromContextMenu(teamDocName)
      await expectOfficePreviewMode('可编辑')
      await closePreviewModal()

      await setAuthTokens(viewer.accessToken, viewer.refreshToken)
      await browser.refresh()
      await openSidebarLink(team.name)
      await expectFileVisible(viewerFileName)
      await expectWriteActionsUnavailable()
      await openFileContextMenu(viewerFileName)
      await expectContextMenuItemVisible('下载')
      await expectContextMenuItemVisible('收藏')
      await expectContextMenuItemNotVisible('分享')
      await expectContextMenuItemNotVisible('标签')
      await expectContextMenuItemNotVisible('删除')
    } finally {
      await request.dispose()
    }
  })
})

async function resetToLogin() {
  await browser.execute(() => {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
  }).catch(() => undefined)
  await browser.refresh().catch(() => undefined)
  await waitForTauriWindow()
  assert.ok(await browser.getTitle())
}
