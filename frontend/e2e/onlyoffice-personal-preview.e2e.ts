import { test, expect } from './fixtures/test'
import {
  addTeamMember,
  createMinimalDocx,
  createMinimalPptx,
  createMinimalXlsx,
  createTeam,
  createUser,
  getFile,
  getOfficeConfig,
  getTeamFile,
  getTeamOfficeConfig,
  loginViaUi,
  uniqueName,
  uploadSampleFile,
  uploadTeamSampleFile,
} from './helpers/api'

const onlyOfficeURL = process.env.E2E_ONLYOFFICE_URL || 'http://127.0.0.1:8082'

test.describe('onlyoffice personal preview', () => {
  test.describe.configure({ timeout: 180_000 })
  test.skip(process.env.E2E_ONLYOFFICE !== '1', 'OnlyOffice E2E is opt-in with E2E_ONLYOFFICE=1')

  test('opens personal docx, pptx, and xlsx files as read-only previews', async ({ page, request, e2eUser }) => {
    const api = await request.get(`${onlyOfficeURL}/web-apps/apps/api/documents/api.js`)
    expect(api.ok(), `OnlyOffice Document Server must be reachable at ${onlyOfficeURL}`).toBe(true)

    const files = [
      {
        name: `${uniqueName('personal_doc')}.docx`,
        body: createMinimalDocx('Personal docx read-only preview'),
        documentType: 'word',
      },
      {
        name: `${uniqueName('personal_sheet')}.xlsx`,
        body: createMinimalXlsx('Personal xlsx read-only preview'),
        documentType: 'cell',
      },
      {
        name: `${uniqueName('personal_slide')}.pptx`,
        body: createMinimalPptx('Personal pptx read-only preview'),
        documentType: 'slide',
      },
    ]

    const uploadedFiles: Array<{ id: number; name: string; versionCount: number }> = []
    for (const file of files) {
      const upload = await uploadSampleFile(request, e2eUser.accessToken, null, file.name, file.body)
      const before = await getFile(request, e2eUser.accessToken, upload.fileId)
      uploadedFiles.push({ id: upload.fileId, name: file.name, versionCount: before.versionCount ?? 1 })
      const officeConfig = await getOfficeConfig(request, e2eUser.accessToken, upload.fileId)
      const editorConfig = officeConfig.config.editorConfig as { mode?: string; callbackUrl?: string }
      expect(officeConfig.editable).toBe(false)
      expect(officeConfig.config.documentType).toBe(file.documentType)
      expect(editorConfig.mode).toBe('view')
      expect(editorConfig.callbackUrl).toBeUndefined()
      expect((officeConfig.config.document as { permissions?: { edit?: boolean } }).permissions?.edit).toBe(false)
      expectReadonlyOfficeConfig(officeConfig.config)
      expect(before.versionCount ?? 1).toBe(1)
    }

    await loginViaUi(page, e2eUser)

    for (const file of files) {
      await page.goto('/files')
      await expect(page.getByTestId(`file-card-${file.name}`)).toBeVisible()
      await page.getByTestId(`file-card-${file.name}`).click({ button: 'right' })
      await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()

      const dialog = page.getByRole('dialog', { name: '文件预览' })
      await expect(dialog).toBeVisible()
      await expect(dialog.getByText('只读预览')).toBeVisible()
      await expect(dialog.locator('iframe').first()).toBeVisible({ timeout: 60_000 })
      await dialog.getByRole('button', { name: '关闭' }).last().click()
    }

    for (const file of uploadedFiles) {
      const after = await getFile(request, e2eUser.accessToken, file.id)
      expect(after.versionCount ?? 1, file.name).toBe(file.versionCount)
    }
  })

  test('opens team documents as read-only when the member lacks write permission', async ({ page, request, e2eUser }) => {
    const api = await request.get(`${onlyOfficeURL}/web-apps/apps/api/documents/api.js`)
    expect(api.ok(), `OnlyOffice Document Server must be reachable at ${onlyOfficeURL}`).toBe(true)

    const viewer = await createUser(request)
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('oo_readonly_team'))
    const upload = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('team_readonly')}.docx`,
      createMinimalDocx('Team viewer read-only preview'),
    )
    await addTeamMember(request, e2eUser.accessToken, team.id, viewer.username, 'VIEWER')

    const officeConfig = await getTeamOfficeConfig(request, viewer.accessToken, team.id, upload.fileId)
    expect(officeConfig.editable).toBe(false)
    expect(officeConfig.config.documentType).toBe('word')
    expectReadonlyOfficeConfig(officeConfig.config)

    await loginViaUi(page, viewer)
    await page.goto(`/teams/${team.id}/files`)
    await expect(page.getByTestId(`file-card-${upload.fileName}`)).toBeVisible()
    await page.getByTestId(`file-card-${upload.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()

    const dialog = page.getByRole('dialog', { name: '文件预览' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('只读预览')).toBeVisible()
    await expect(dialog.locator('iframe').first()).toBeVisible({ timeout: 60_000 })
    await dialog.getByRole('button', { name: '关闭' }).last().click()

    const after = await getTeamFile(request, e2eUser.accessToken, team.id, upload.fileId)
    expect(after.versionCount ?? 1).toBe(1)
  })
})

function expectReadonlyOfficeConfig(config: Record<string, unknown>) {
  const editorConfig = config.editorConfig as { mode?: string; callbackUrl?: string; customization?: Record<string, unknown> }
  const document = config.document as { permissions?: Record<string, unknown> }
  expect(editorConfig.mode).toBe('view')
  expect(editorConfig.callbackUrl).toBeUndefined()
  expect(editorConfig.customization).toMatchObject({
    plugins: false,
    macros: false,
    macrosMode: 'disable',
  })
  expect(document.permissions).toMatchObject({
    edit: false,
    review: false,
    comment: false,
  })
}
