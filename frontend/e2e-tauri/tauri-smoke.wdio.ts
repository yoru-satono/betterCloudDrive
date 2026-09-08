import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  createFolder,
  createTeam,
  createTeamFolder,
  createUser,
  listNotifications,
  listFiles,
  listTeamFiles,
  listTeamInvites,
  listTeamMembers,
  uniqueName,
  uploadSampleFile,
  uploadTeamSampleFile,
} from '../e2e/helpers/api'
import { createApiRequestContext } from './helpers/api-request'
import {
  captureClipboardWrites,
  clickButton,
  createShareFromContextMenu,
  createTeamShareFromContextMenu,
  downloadFromContextMenu,
  expectFolderUploadAvailable,
  expectDesktopSettings,
  expectFileVisible,
  expectVisibleText,
  loginViaWindow,
  openSidebarLink,
  openTeamRecycleBin,
  openTeamSettings,
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
      const invitee = await createUser(request)
      const fileName = `${uniqueName('tauri-smoke')}.txt`
      const fileContents = `desktop resumable download ${Date.now()}`
      const file = await uploadSampleFile(request, user.accessToken, null, fileName, fileContents)
      const folder = await createFolder(request, user.accessToken, uniqueName('tauri-download-folder'))
      const childContents = `desktop folder download ${Date.now()}`
      await uploadSampleFile(request, user.accessToken, folder.id, 'nested.txt', childContents)
      const team = await createTeam(request, user.accessToken, uniqueName('tauri-team'))
      const teamFile = await uploadTeamSampleFile(
        request,
        user.accessToken,
        team.id,
        null,
        `${uniqueName('tauri-team-file')}.txt`,
        `desktop team file ${Date.now()}`,
      )
      const teamFolder = await createTeamFolder(request, user.accessToken, team.id, uniqueName('tauri-team-folder'))
      const droppedTeamFileName = `${uniqueName('tauri-team-upload')}.txt`
      const droppedTeamFileContents = `desktop team upload ${Date.now()}`

      await waitForTauriWindow()
      await expectDesktopSettings()
      await loginViaWindow(user.username, user.password)
      await expectFileVisible(file.fileName)
      await expectFileVisible(folder.fileName)
      await expectFolderUploadAvailable()
      await expectThemeSelection()
      await openSidebarLink('全部文件')
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
      assertCanonicalShareUrl(clipboardText)

      await openSidebarLink(team.name)
      await expectFileVisible(teamFile.fileName)
      await expectFileVisible(teamFolder.fileName)
      await dropTeamFileOnCurrentPage(droppedTeamFileName, droppedTeamFileContents)
      await expectFileVisible(droppedTeamFileName)
      await expectTeamFileUploaded(request, user.accessToken, team.id, droppedTeamFileName, droppedTeamFileContents.length)
      await createTeamShareFromContextMenu(droppedTeamFileName)
      const teamClipboardText = await readCapturedClipboardText()
      assertCanonicalShareUrl(teamClipboardText)
      await openTeamSettings()
      await closeTransferQueueIfOpen()
      await sendInviteFromTeamSettings(invitee.email)
      await expectInvitePending(request, user.accessToken, team.id, invitee.email)
      await expectInviteNotification(request, invitee.accessToken, team.id)
      await acceptInviteFromNotification(invitee.accessToken, invitee.refreshToken, team.name)
      await expectTeamMemberRole(request, invitee.accessToken, team.id, invitee.userId, 'EDITOR')
      await openSidebarLink(team.name)
      await openTeamRecycleBin()
    } finally {
      await request.dispose()
    }
  })
})

function assertCanonicalShareUrl(value: string) {
  const shareUrl = new URL(value)
  assert.equal(shareUrl.origin, process.env.E2E_PUBLIC_URL || 'http://localhost:3000')
  assert.match(shareUrl.pathname, /^\/s\/[^/]+$/)
}

async function expectThemeSelection() {
  await (await $('.sidebar__user')).click()
  const darkOption = await $('[data-testid="theme-option-dark"]')
  await darkOption.waitForDisplayed()
  await darkOption.click()
  await browser.waitUntil(async () => (await $('html').getAttribute('data-theme')) === 'dark', {
    timeout: 5_000,
    timeoutMsg: 'Desktop theme did not switch to dark mode',
  })
  const storedTheme = await browser.execute(() => localStorage.getItem('bcd.themeMode'))
  assert.equal(storedTheme, 'dark')
  const systemOption = await $('[data-testid="theme-option-system"]')
  await systemOption.click()
}

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

async function closeTransferQueueIfOpen() {
  const queue = await $('.transfer-queue')
  if (!(await queue.isDisplayed().catch(() => false))) return
  const buttons = await queue.$$('.transfer-queue__header-actions button')
  if (!buttons.length) return
  await buttons[Number(buttons.length) - 1].click()
  await browser.waitUntil(async () => !(await queue.isDisplayed().catch(() => false)), {
    timeout: 5_000,
    timeoutMsg: 'Transfer queue did not close',
  })
}

async function dropTeamFileOnCurrentPage(fileName: string, contents: string) {
  await browser.execute((name, body) => {
    const target = document.querySelector('.team-files')
    if (!target) throw new Error('Team files drop target was not found')
    const dataTransfer = new DataTransfer()
    dataTransfer.items.add(new File([body], name, { type: 'text/plain' }))
    target.dispatchEvent(new DragEvent('drop', {
      bubbles: true,
      cancelable: true,
      dataTransfer,
    }))
  }, fileName, contents)
}

async function expectTeamFileUploaded(
  request: Awaited<ReturnType<typeof createApiRequestContext>>,
  token: string,
  teamId: number,
  fileName: string,
  fileSize: number,
) {
  await browser.waitUntil(async () => {
    const files = await listTeamFiles(request, token, teamId)
    return files.some(item => item.fileName === fileName && item.fileSize === fileSize)
  }, {
    timeout: 20_000,
    timeoutMsg: `Team uploaded file ${fileName} did not appear in API list`,
  })
}

async function sendInviteFromTeamSettings(email: string) {
  const inviteSection = await sectionByHeading('邀请')
  await (await inviteSection.$('input[placeholder="邮箱地址"]')).setValue(email)
  if (process.env.BCD_TAURI_E2E_DRIVER === 'embedded') {
    await browser.execute(() => {
      const section = Array.from(document.querySelectorAll<HTMLElement>('section.team-section'))
        .find(candidate => candidate.querySelector('h3')?.textContent?.trim() === '邀请')
      const select = section?.querySelector<HTMLSelectElement>('select')
      if (!select) throw new Error('Invite role selector was not found')
      select.value = 'EDITOR'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })
  } else {
    await (await inviteSection.$('select')).selectByAttribute('value', 'EDITOR')
  }
  await (await inviteSection.$('.//button[normalize-space(.)="发送邀请"]')).click()
  await waitForTextWithin(inviteSection, email)
}

async function expectInvitePending(
  request: Awaited<ReturnType<typeof createApiRequestContext>>,
  token: string,
  teamId: number,
  email: string,
) {
  await browser.waitUntil(async () => {
    const invites = await listTeamInvites(request, token, teamId)
    return invites.find(invite => invite.email === email)?.status === 'PENDING'
  }, {
    timeout: 15_000,
    timeoutMsg: `Invite for ${email} was not pending`,
  })
}

async function expectInviteNotification(
  request: Awaited<ReturnType<typeof createApiRequestContext>>,
  token: string,
  teamId: number,
) {
  await browser.waitUntil(async () => {
    const notifications = await listNotifications(request, token, { unreadOnly: true, type: 'TEAM_INVITE' })
    return notifications.some(notification =>
      notification.teamId === teamId &&
      notification.titleKey === 'notification.teamInvite.title' &&
      !!notification.link?.startsWith('/team-invites/'),
    )
  }, {
    timeout: 15_000,
    timeoutMsg: `Invite notification for team ${teamId} was not created`,
  })
}

async function acceptInviteFromNotification(accessToken: string, refreshToken: string, teamName: string) {
  await setAuthTokens(accessToken, refreshToken)
  await navigateToDesktopPath('/notifications')
  await expectVisibleText('通知')
  await (await $('.notifications-page__type')).selectByAttribute('value', 'TEAM_INVITE')
  const notificationRow = await $(`//button[contains(@class, "notification-row") and contains(normalize-space(.), "${teamName}")]`)
  await notificationRow.waitForDisplayed()
  if (process.env.BCD_TAURI_E2E_DRIVER === 'embedded') {
    await browser.execute((name) => {
      const row = Array.from(document.querySelectorAll<HTMLButtonElement>('button.notification-row'))
        .find(candidate => candidate.textContent?.includes(name))
      if (!row) throw new Error(`Notification row for ${name} was not found`)
      row.click()
    }, teamName)
  } else {
    await notificationRow.click()
  }
  await browser.waitUntil(async () => /\/team-invites\/[^/?#]+/.test(await browser.getUrl()), {
    timeout: 15_000,
    timeoutMsg: 'Opening the invite notification did not navigate to the invite page',
  })
  await expectVisibleText(teamName)
  const acceptButton = await $('//button[normalize-space(.)="接受邀请"]')
  await acceptButton.waitForDisplayed()
  if (process.env.BCD_TAURI_E2E_DRIVER === 'embedded') {
    await browser.execute(() => {
      const button = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
        .find(candidate => candidate.textContent?.trim() === '接受邀请')
      if (!button) throw new Error('Accept invite button was not found')
      button.click()
    })
    await browser.waitUntil(async () => /\/teams\/\d+\/files/.test(await browser.getUrl()), {
      timeout: 15_000,
      timeoutMsg: 'Accepting the team invite did not navigate to the team files page',
    })
  } else {
    await acceptButton.click()
    await expectVisibleText(teamName)
  }
}

async function expectTeamMemberRole(
  request: Awaited<ReturnType<typeof createApiRequestContext>>,
  token: string,
  teamId: number,
  userId: number,
  role: 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER',
) {
  await browser.waitUntil(async () => {
    const members = await listTeamMembers(request, token, teamId)
    return members.find(member => member.userId === userId)?.role === role
  }, {
    timeout: 15_000,
    timeoutMsg: `Team member ${userId} did not become ${role}`,
  })
}

async function navigateToDesktopPath(path: string) {
  await browser.execute((nextPath) => {
    window.location.hash = `#${nextPath}`
  }, path)
}

async function sectionByHeading(heading: string) {
  const section = await $(`//section[contains(@class, "team-section") and .//h3[normalize-space(.)="${heading}"]]`)
  await section.waitForDisplayed()
  return section
}

async function waitForTextWithin(element: ReturnType<typeof $>, text: string) {
  await browser.waitUntil(async () => (await element.$(`.//*[contains(normalize-space(.), "${text}")]`).isDisplayed().catch(() => false)), {
    timeout: 10_000,
    timeoutMsg: `Text "${text}" was not displayed`,
  })
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
