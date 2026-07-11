import type { Page } from '@playwright/test'
import { test, expect } from './fixtures/test'
import {
  createMinimalDocx,
  createTeam,
  getTeamOfficeConfig,
  loginViaUi,
  uniqueName,
  uploadTeamSampleFile,
} from './helpers/api'

const onlyOfficeURL = process.env.E2E_ONLYOFFICE_URL || 'http://127.0.0.1:8082'
const onlyOfficeAiPluginGuid = 'asc.{9DC93CDB-B576-4F0C-B55E-FCC9C48DD007}'

test.describe('onlyoffice disabled features', () => {
  test.describe.configure({ timeout: 180_000 })
  test.skip(process.env.E2E_ONLYOFFICE !== '1', 'OnlyOffice E2E is opt-in with E2E_ONLYOFFICE=1')

  test('does not expose AI or plugin entry points in the editor', async ({ page, request, e2eUser }) => {
    const api = await request.get(`${onlyOfficeURL}/web-apps/apps/api/documents/api.js`)
    expect(api.ok(), `OnlyOffice Document Server must be reachable at ${onlyOfficeURL}`).toBe(true)

    const team = await createTeam(request, e2eUser.accessToken, uniqueName('oo_features_team'))
    const upload = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('onlyoffice_features')}.docx`,
      createMinimalDocx('OnlyOffice disabled features baseline'),
    )
    const officeConfig = await getTeamOfficeConfig(request, e2eUser.accessToken, team.id, upload.fileId)
    expectDisabledFeatureConfig(officeConfig.config)

    await loginViaUi(page, e2eUser)
    await page.goto(`/teams/${team.id}/files`)
    await expect(page.getByTestId(`file-card-${upload.fileName}`)).toBeVisible()
    await page.getByTestId(`file-card-${upload.fileName}`).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()

    const dialog = page.getByRole('dialog', { name: '文件预览' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('可编辑')).toBeVisible()
    await expectOnlyOfficeFeatureTabsHidden(page)
    await dialog.getByRole('button', { name: '关闭' }).last().click()
  })
})

function expectDisabledFeatureConfig(config: Record<string, unknown>) {
  const editorConfig = config.editorConfig as {
    customization?: Record<string, unknown>
    plugins?: Record<string, unknown>
  }
  expect(editorConfig.customization).toMatchObject({
    plugins: false,
    macros: false,
    macrosMode: 'disable',
  })
  expect(editorConfig.plugins).toMatchObject({
    autostart: [],
    pluginsData: [],
  })
  expect(editorConfig.plugins?.disable).toEqual(expect.arrayContaining([onlyOfficeAiPluginGuid]))
}

async function expectOnlyOfficeFeatureTabsHidden(page: Page) {
  const iframe = page.getByRole('dialog', { name: '文件预览' }).locator('iframe').first()
  await expect(iframe).toBeVisible({ timeout: 60_000 })

  await expect.poll(async () => {
    const frames = page.frames().filter((frame) => frame !== page.mainFrame())
    for (const frame of frames) {
      const text = await frame.locator('body').innerText({ timeout: 1_000 }).catch(() => '')
      const normalized = text.replace(/\s+/g, ' ').trim()
      if (hasDisabledFeatureLabel(normalized)) {
        return normalized
      }
    }
    return 'hidden'
  }, { timeout: 60_000, intervals: [2_000, 5_000] }).toBe('hidden')
}

function hasDisabledFeatureLabel(text: string) {
  return /(^|\s)AI($|\s)/.test(text) || /(^|\s)Plugins?($|\s)/i.test(text) || text.includes('插件')
}
