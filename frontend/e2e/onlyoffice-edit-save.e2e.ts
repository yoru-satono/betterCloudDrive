import { APIRequestContext, Page } from '@playwright/test'
import crypto from 'node:crypto'
import { test, expect } from './fixtures/test'
import {
  createMinimalDocx,
  createMinimalPptx,
  createMinimalXlsx,
  createTeam,
  downloadTeamFileBytes,
  getTeamFile,
  getTeamOfficeConfig,
  listTeamVersions,
  loginViaUi,
  uniqueName,
  uploadTeamSampleFile,
  zipContainsText,
} from './helpers/api'

const onlyOfficeURL = process.env.E2E_ONLYOFFICE_URL || 'http://127.0.0.1:8082'
const onlyOfficeJwtSecret = process.env.ONLYOFFICE_JWT_SECRET || 'betterclouddrive-onlyoffice-dev-secret'
const officeSaveCases = [
  {
    extension: 'docx',
    documentType: 'word',
    createBody: () => createMinimalDocx('OnlyOffice docx edit save baseline'),
  },
  {
    extension: 'xlsx',
    documentType: 'cell',
    createBody: () => createMinimalXlsx('OnlyOffice xlsx edit save baseline'),
  },
  {
    extension: 'pptx',
    documentType: 'slide',
    createBody: () => createMinimalPptx('OnlyOffice pptx edit save baseline'),
  },
] as const

test.describe('onlyoffice edit save', () => {
  test.describe.configure({ timeout: 180_000 })
  test.skip(process.env.E2E_ONLYOFFICE !== '1', 'OnlyOffice E2E is opt-in with E2E_ONLYOFFICE=1')

  for (const officeCase of officeSaveCases) {
    test(`saves team ${officeCase.extension} edits through the real OnlyOffice forcesave callback and creates a version`, async ({ page, request, e2eUser }) => {
      const api = await request.get(`${onlyOfficeURL}/web-apps/apps/api/documents/api.js`).catch(() => null)
      test.skip(!api?.ok(), `OnlyOffice Document Server is not reachable at ${onlyOfficeURL}`)

      const team = await createTeam(request, e2eUser.accessToken, uniqueName('oo_team'))
      const upload = await uploadTeamSampleFile(
        request,
        e2eUser.accessToken,
        team.id,
        null,
        `${uniqueName(`onlyoffice_${officeCase.extension}`)}.${officeCase.extension}`,
        officeCase.createBody(),
      )
      const fileId = upload.fileId
      const beforeFile = await getTeamFile(request, e2eUser.accessToken, team.id, fileId)
      const beforeVersions = await listTeamVersions(request, e2eUser.accessToken, team.id, fileId)
      const officeConfig = await getTeamOfficeConfig(request, e2eUser.accessToken, team.id, fileId)
      expectEditableOfficeConfig(officeConfig.config, officeCase.documentType)
      const marker = `OO_${officeCase.extension.toUpperCase()}_${Date.now()}`

      await loginViaUi(page, e2eUser)
      await page.goto(`/teams/${team.id}/files`)
      await expect(page.getByTestId(`file-card-${upload.fileName}`)).toBeVisible()

      await page.getByTestId(`file-card-${upload.fileName}`).click({ button: 'right' })
      await page.getByRole('menu').getByRole('menuitem', { name: '打开文档' }).click()
      const dialog = page.getByRole('dialog', { name: '文件预览' })
      await expect(dialog).toBeVisible()
      await expect(dialog.getByText('可编辑')).toBeVisible()

      await typeIntoOnlyOffice(page, marker, officeCase.documentType)
      await forceSaveOnlyOfficeDocument(request, officeConfig.config)

      await expect.poll(async () => {
        const current = await getTeamFile(request, e2eUser.accessToken, team.id, fileId)
        const versions = await listTeamVersions(request, e2eUser.accessToken, team.id, fileId)
        const downloaded = await downloadTeamFileBytes(request, e2eUser.accessToken, team.id, fileId)
        const fileChanged = current.md5Hash !== beforeFile.md5Hash || current.fileSize !== beforeFile.fileSize
        const versionAdded = versions.length > beforeVersions.length && (current.versionCount ?? 0) > (beforeFile.versionCount ?? 0)
        const markerSaved = zipContainsText(downloaded, marker)
        return fileChanged && versionAdded && markerSaved ? 'saved' : `waiting ${versions.length}/${current.versionCount}`
      }, { timeout: 120_000, intervals: [2_000, 5_000, 10_000] }).toBe('saved')

      const downloaded = await downloadTeamFileBytes(request, e2eUser.accessToken, team.id, fileId)
      expect(downloaded.length).toBeGreaterThan(0)
      expect(downloaded.subarray(0, 2).toString()).toBe('PK')
      expect(zipContainsText(downloaded, marker)).toBe(true)
      await dialog.getByRole('button', { name: '关闭' }).last().click()
    })
  }
})

async function typeIntoOnlyOffice(page: Page, marker: string, documentType: 'word' | 'cell' | 'slide') {
  const iframe = page.getByRole('dialog', { name: '文件预览' }).locator('iframe').first()
  await expect(iframe).toBeVisible({ timeout: 60_000 })
  await page.waitForTimeout(8_000)

  const frameHandle = await iframe.elementHandle()
  const frame = await frameHandle?.contentFrame()
  if (frame) {
    await frame.getByRole('button', { name: /知道了|Got it|OK/i }).click({ timeout: 5_000 }).catch(() => null)
    await frame.locator('body').click({ position: { x: 520, y: 360 }, timeout: 10_000 })
  }

  const box = await iframe.boundingBox()
  if (!box) throw new Error('OnlyOffice iframe has no bounding box')
  const x = box.x + Math.min(box.width * 0.48, box.width - 120)
  const y = box.y + Math.min(box.height * 0.48, box.height - 120)
  if (documentType === 'slide') {
    await page.mouse.dblclick(x, y)
    await page.keyboard.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
    await page.keyboard.type(marker, { delay: 20 })
  } else if (documentType === 'cell') {
    await page.mouse.click(x, y)
    await page.keyboard.type(marker, { delay: 20 })
    await page.keyboard.press('Enter')
  } else {
    await page.mouse.click(x, y)
    await page.keyboard.press('End')
    await page.keyboard.press('Enter')
    await page.keyboard.type(marker, { delay: 20 })
  }
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+S' : 'Control+S')
  await page.waitForTimeout(4_000)
}

async function forceSaveOnlyOfficeDocument(request: APIRequestContext, config: Record<string, unknown>) {
  const document = config.document as { key?: string } | undefined
  if (!document?.key) throw new Error('OnlyOffice document key is missing')
  const payload = { c: 'forcesave', key: document.key }
  const body = { ...payload, token: signOnlyOfficeJwt(payload) }
  const endpoints = [`${onlyOfficeURL}/command`, `${onlyOfficeURL}/coauthoring/CommandService.ashx`]
  let lastBody: unknown = null
  for (const endpoint of endpoints) {
    const res = await request.post(endpoint, {
      headers: { Authorization: `Bearer ${body.token}` },
      data: body,
    }).catch(() => null)
    if (!res) continue
    const text = await res.text()
    if (!res.ok()) {
      lastBody = text
      continue
    }
    const json = JSON.parse(text) as { error?: number }
    lastBody = json
    if (json.error === 0) return
  }
  throw new Error(`OnlyOffice forcesave failed: ${JSON.stringify(lastBody)}`)
}

function signOnlyOfficeJwt(payload: Record<string, unknown>) {
  const header = { alg: 'HS256', typ: 'JWT' }
  const encodedHeader = base64Url(Buffer.from(JSON.stringify(header)))
  const encodedPayload = base64Url(Buffer.from(JSON.stringify(payload)))
  const signature = crypto
    .createHmac('sha256', onlyOfficeJwtSecret)
    .update(`${encodedHeader}.${encodedPayload}`)
    .digest()
  return `${encodedHeader}.${encodedPayload}.${base64Url(signature)}`
}

function base64Url(buffer: Buffer) {
  return buffer.toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

function expectEditableOfficeConfig(config: Record<string, unknown>, documentType: string) {
  const editorConfig = config.editorConfig as { mode?: string; callbackUrl?: string; customization?: Record<string, unknown> }
  const document = config.document as { permissions?: Record<string, unknown> }
  expect(config.documentType).toBe(documentType)
  expect(editorConfig.mode).toBe('edit')
  expect(editorConfig.callbackUrl).toContain('/callback?token=')
  expect(editorConfig.customization).toMatchObject({
    plugins: false,
    macros: false,
    macrosMode: 'disable',
  })
  expect(document.permissions).toMatchObject({
    edit: true,
    review: true,
    comment: true,
  })
}
