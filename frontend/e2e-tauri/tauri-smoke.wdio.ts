import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  createFolder,
  createUser,
  listFiles,
  uniqueName,
  uploadSampleFile,
} from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import {
  captureClipboardWrites,
  createShareFromContextMenu,
  downloadFromContextMenu,
  expectFolderUploadAvailable,
  expectDesktopSettings,
  expectFileVisible,
  loginViaWindow,
  readCapturedClipboardText,
  setAuthTokens,
  uploadFolderViaPicker,
  waitForTauriWindow,
} from './helpers/wdio-ui'

const downloadDir = process.env.BCD_E2E_DOWNLOAD_DIR || path.join(os.tmpdir(), 'bcd-tauri-e2e-downloads')

describe('Tauri desktop window smoke', () => {
  it('logs in, displays files, resumes downloads, downloads folders, and creates web share links', async () => {
    const request = await createApiRequestContext()
    try {
      await fs.rm(downloadDir, { recursive: true, force: true })
      await fs.mkdir(downloadDir, { recursive: true })

      const user = await createUser(request)
      const fileName = `${uniqueName('tauri-smoke')}.txt`
      const fileContents = `desktop resumable download ${Date.now()}`
      const file = await uploadSampleFile(request, user.accessToken, null, fileName, fileContents)
      const folder = await createFolder(request, user.accessToken, uniqueName('tauri-download-folder'))
      const childContents = `desktop folder download ${Date.now()}`
      await uploadSampleFile(request, user.accessToken, folder.id, 'nested.txt', childContents)

      await waitForTauriWindow()
      await expectDesktopSettings()
      await loginViaWindow(user.username, user.password)
      await expectFileVisible(file.fileName)
      await expectFileVisible(folder.fileName)
      await expectFolderUploadAvailable()
      await captureClipboardWrites()

      const emptyUploadName = 'empty.txt'
      const folderName = await uploadFolderViaPicker(uniqueName('tauri-folder'), emptyUploadName, '')

      await setAuthTokens('expired-access-token', user.refreshToken)
      const partial = fileContents.slice(0, 8)
      await fs.writeFile(path.join(downloadDir, `${file.fileName}.part`), partial)
      await downloadFromContextMenu(file.fileName)
      const downloadedFilePath = path.join(downloadDir, file.fileName)
      await waitForFileContents(downloadedFilePath, fileContents)
      assert.equal(await fileExists(path.join(downloadDir, `${file.fileName}.part`)), false)

      await downloadFromContextMenu(folder.fileName)
      const downloadedFolderFile = path.join(downloadDir, folder.fileName, 'nested.txt')
      await waitForFileContents(downloadedFolderFile, childContents)

      await expectApiChildFile(request, user.accessToken, folderName, emptyUploadName, 0)

      await createShareFromContextMenu(file.fileName)
      const clipboardText = await readCapturedClipboardText()
      assert.match(clipboardText, /^http:\/\/127\.0\.0\.1:3000\/s\/\S+/)
    } finally {
      await request.dispose()
    }
  })
})

async function waitForFileContents(filePath: string, expected: string) {
  await browser.waitUntil(async () => {
    const value = await fs.readFile(filePath, 'utf8').catch(() => null)
    return value === expected
  }, {
    timeout: 20_000,
    timeoutMsg: `Expected downloaded file at ${filePath}`,
  })
}

async function fileExists(filePath: string) {
  return fs.access(filePath).then(() => true, () => false)
}

async function rootFileByName(request: Awaited<ReturnType<typeof createApiRequestContext>>, token: string, fileName: string) {
  await browser.waitUntil(async () => {
    const files = await listFiles(request, token)
    return files.some(item => item.fileName === fileName)
  }, {
    timeout: 20_000,
    timeoutMsg: `Uploaded folder ${fileName} did not appear in API list`,
  })

  const files = await listFiles(request, token)
  const file = files.find(item => item.fileName === fileName)
  assert.ok(file, `Uploaded folder ${fileName} did not exist`)
  return file
}

async function expectApiChildFile(
  request: Awaited<ReturnType<typeof createApiRequestContext>>,
  token: string,
  folderName: string,
  childName: string,
  expectedSize?: number,
) {
  const folder = await rootFileByName(request, token, folderName)
  await browser.waitUntil(async () => {
    const children = await listFiles(request, token, folder.id)
    return children.some(item => item.fileName === childName && (expectedSize === undefined || item.fileSize === expectedSize))
  }, {
    timeout: 20_000,
    timeoutMsg: `Uploaded folder ${folderName} did not contain ${childName}`,
  })
}
