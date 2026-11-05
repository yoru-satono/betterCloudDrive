import fs from 'node:fs/promises'
import path from 'node:path'
import { $, $$, browser, expect } from '@wdio/globals'

export async function waitForTauriWindow() {
  try {
    await browser.waitUntil(async () => (await $('//label[normalize-space(.)="用户名"]').isDisplayed().catch(() => false)), {
      timeout: 30_000,
      timeoutMsg: 'BetterCloudDrive login screen did not become available.',
    })
  } catch (error) {
    throw new Error(`${await getWindowDiagnostics()}\n${error instanceof Error ? error.message : String(error)}`)
  }
}

export async function resetToLoginWindow() {
  await browser.execute(() => {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    window.location.hash = '#/login'
  }).catch(() => undefined)
  await browser.refresh().catch(() => undefined)
  await waitForTauriWindow()
}

export async function loginViaWindow(username: string, password: string) {
  await (await inputByLabel('用户名')).setValue(username)
  await (await inputByLabel('密码')).setValue(password)
  await (await buttonByText('登录')).click()
  await expect(await linkByText('全部文件')).toBeDisplayed()
}

export async function fillInputByLabel(label: string, value: string) {
  await (await inputByLabel(label)).setValue(value)
}

export async function clickButton(text: string) {
  await (await buttonByText(text)).click()
}

export async function clickLink(text: string) {
  await (await linkByText(text)).click()
}

export async function clickTextContaining(text: string) {
  const element = await textElementContaining(text)
  await element.waitForDisplayed()
  await element.click()
}

export async function clickElementByClassContaining(className: string, text: string) {
  const element = await $(`//*[contains(concat(" ", normalize-space(@class), " "), " ${className} ") and contains(normalize-space(.), "${text}")]`)
  await element.waitForDisplayed()
  await element.click()
}

export async function expectVisibleText(text: string) {
  await expectTextVisible(text)
}

export async function expectDesktopSettings() {
  await (await buttonByLabel('连接设置')).click()
  const apiInput = await inputByLabel('API 地址')
  const webInput = await inputByLabel('Web 分享地址')
  const apiUrl = `${(process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')}/api/v1`
  const webUrl = process.env.E2E_BASE_URL || 'http://127.0.0.1:3000'
  await apiInput.setValue(apiUrl)
  await webInput.setValue(webUrl)
  await expect(apiInput).toHaveValue(apiUrl)
  await expect(webInput).toHaveValue(webUrl)
  await (await buttonByText('保存')).click()
  await expect(apiInput).not.toBeDisplayed()
}

export async function expectFileVisible(fileName: string) {
  await browser.waitUntil(async () => (await fileCard(fileName).isDisplayed().catch(() => false)), {
    timeout: 20_000,
    timeoutMsg: `File card for ${fileName} was not visible`,
  })
}

export async function expectFolderUploadAvailable() {
  await expect(await buttonByText('上传文件夹')).toBeDisplayed()
}

export async function openFileContextMenu(fileName: string) {
  const card = await fileCard(fileName)
  if (process.env.BCD_TAURI_E2E_DRIVER === 'embedded') {
    await browser.waitUntil(async () => browser.execute((testId) => {
      const element = Array.from(document.querySelectorAll<HTMLElement>('[data-testid]'))
        .find(candidate => candidate.dataset.testid === testId)
      if (!element) return false
      element.dispatchEvent(new MouseEvent('contextmenu', {
        bubbles: true,
        cancelable: true,
        button: 2,
        buttons: 2,
      }))
      return true
    }, `file-card-${fileName}`), {
      timeout: 10_000,
      timeoutMsg: `File card for ${fileName} was not available for a context menu`,
    })
  } else {
    await card.click({ button: 'right' })
  }
  await expect(await menuItemByText('下载')).toBeDisplayed()
}

export async function createShareFromContextMenu(fileName: string) {
  await openFileContextMenu(fileName)
  await (await menuItemByText('分享')).click()
  await expect(await $('[data-testid="share-modal"]')).toBeDisplayed()
  await (await buttonByText('创建并复制链接')).click()
  await expect(await $('[data-testid="share-modal"]')).not.toBeDisplayed()
}

export async function openSidebarLink(text: string) {
  let link = await $(`//a[normalize-space(.)="${text}"]`)
  if (!(await link.isDisplayed().catch(() => false))) {
    const switchToTeams = await $('//button[normalize-space(.)="切换到团队空间"]')
    if (await switchToTeams.isDisplayed().catch(() => false)) {
      await switchToTeams.click()
    }
    link = await linkByText(text)
  }
  await link.click()
  await expectTextVisible(text)
}

export async function createTeamShareFromContextMenu(fileName: string) {
  await openFileContextMenu(fileName)
  await (await menuItemByText('分享')).click()
  await expect(await $('[data-testid="team-share-modal"]')).toBeDisplayed()
  await (await buttonByText('创建并复制链接')).click()
  await expect(await $('[data-testid="team-share-modal"]')).not.toBeDisplayed()
}

export async function openOfficeFromContextMenu(fileName: string) {
  await openFileContextMenu(fileName)
  await (await menuItemByText('打开文档')).click()
  await expectTextVisible('文件预览')
  await expect(await $('[data-testid="onlyoffice-shell"]')).toBeDisplayed()
}

export async function expectOfficePreviewMode(text: '只读预览' | '可编辑') {
  await expectTextVisible(text)
  await browser.waitUntil(async () => (await $('iframe').isDisplayed().catch(() => false)), {
    timeout: 60_000,
    timeoutMsg: `OnlyOffice iframe did not become visible in ${text} mode`,
  })
}

export async function closePreviewModal() {
  const buttons = await $$('//button[normalize-space(.)="关闭"]')
  if (!buttons.length) throw new Error('Close button was not found')
  await buttons[Number(buttons.length) - 1].click()
  await browser.waitUntil(async () => !(await textElementContaining('文件预览').isDisplayed().catch(() => false)), {
    timeout: 10_000,
    timeoutMsg: 'Preview modal did not close',
  })
}

export async function expectContextMenuItemVisible(text: string) {
  await expect(await menuItemByText(text)).toBeDisplayed()
}

export async function expectContextMenuItemNotVisible(text: string) {
  await browser.waitUntil(async () => !(await $(`//*[@role="menuitem" and normalize-space(.)="${text}"]`).isDisplayed().catch(() => false)), {
    timeout: 5_000,
    timeoutMsg: `Context menu item "${text}" was still displayed`,
  })
}

export async function openTeamSettings() {
  await (await buttonByText('设置')).click()
  await expectTextVisible('团队设置')
}

export async function openTeamRecycleBin() {
  await (await buttonByText('回收站')).click()
  await browser.waitUntil(async () => (await textElementContaining('回收站').isDisplayed().catch(() => false)), {
    timeout: 10_000,
    timeoutMsg: 'Team recycle bin did not become visible',
  })
}

export async function expectWriteActionsUnavailable() {
  await expectButtonNotDisplayed('上传文件')
  await expectButtonNotDisplayed('新建文件夹')
}

export async function expectSearchTrigger(text: string) {
  await expectTextVisible(text)
}

export async function openContextSearch(query: string) {
  await (await $('.app-header__search')).click()
  await (await $('.search-panel')).waitForDisplayed()
  const input = await $('.search-input')
  await input.waitForDisplayed()
  await input.setValue(query)
  await (await $('.search-results')).waitForDisplayed()
}

export async function closeContextSearch() {
  await browser.keys('Escape')
  await browser.waitUntil(async () => !(await $('.search-panel').isDisplayed().catch(() => false)), {
    timeout: 5_000,
    timeoutMsg: 'Search panel did not close',
  })
}

export async function expectContextSearchResult(text: string) {
  await browser.waitUntil(async () => (await textElementContaining(text).isDisplayed().catch(() => false)), {
    timeout: 10_000,
    timeoutMsg: `Search result "${text}" was not displayed`,
  })
}

export async function downloadFromContextMenu(fileName: string) {
  await openFileContextMenu(fileName)
  await (await menuItemByText('下载')).click()
}

export async function uploadFolderViaPicker(folderName: string, fileName: string, contents = 'folder upload smoke') {
  const nativeUploadDirectory = process.env.BCD_E2E_UPLOAD_DIR
  if (nativeUploadDirectory) {
    await fs.rm(nativeUploadDirectory, { recursive: true, force: true })
    await fs.mkdir(nativeUploadDirectory, { recursive: true })
    await fs.writeFile(path.join(nativeUploadDirectory, fileName), contents)
    const nativeFolderName = path.basename(nativeUploadDirectory)
    await (await buttonByText('上传文件夹')).click()
    await expectFileVisible(nativeFolderName)
    return nativeFolderName
  }

  await mockFolderPicker(folderName, fileName, contents)
  await (await buttonByText('上传文件夹')).click()
  await expectFileVisible(folderName)
  return folderName
}

export async function setAuthTokens(accessToken: string, refreshToken: string) {
  await browser.execute((access, refresh) => {
    localStorage.setItem('accessToken', access)
    localStorage.setItem('refreshToken', refresh)
  }, accessToken, refreshToken)
}

export async function captureClipboardWrites() {
  await browser.execute(() => {
    const win = globalThis as typeof globalThis & {
      __bcdLastClipboardText?: string
      navigator: Navigator
    }
    win.__bcdLastClipboardText = ''
    const originalClipboard = win.navigator.clipboard
    const originalWriteText = originalClipboard?.writeText?.bind(originalClipboard)
    const clipboard = {
      ...originalClipboard,
      writeText: async (value: string) => {
        win.__bcdLastClipboardText = value
        if (originalWriteText) {
          return originalWriteText(value).catch(() => undefined)
        }
        return undefined
      },
    }
    try {
      Object.defineProperty(win.navigator, 'clipboard', {
        configurable: true,
        value: clipboard,
      })
    } catch {
      if (originalClipboard) {
        originalClipboard.writeText = async (value: string) => {
          win.__bcdLastClipboardText = value
          if (originalWriteText) {
            return originalWriteText(value).catch(() => undefined)
          }
          return undefined
        }
      }
    }
  })
}

export async function captureGeneratedDownloads() {
  await browser.execute(() => {
    const win = globalThis as typeof globalThis & {
      __bcdLastDownloadName?: string
      __bcdDesktopWriteCount?: number
      __bcdOriginalAnchorClick?: typeof HTMLAnchorElement.prototype.click
      showSaveFilePicker?: (options?: { suggestedName?: string }) => Promise<{
        createWritable: () => Promise<{
          write: (value: Blob) => Promise<void>
          close: () => Promise<void>
        }>
      }>
      showDirectoryPicker?: () => Promise<unknown>
    }
    win.__bcdLastDownloadName = ''
    win.__bcdDesktopWriteCount = 0
    if (!win.__bcdOriginalAnchorClick) {
      win.__bcdOriginalAnchorClick = HTMLAnchorElement.prototype.click
    }
    HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement) {
      if (this.download) {
        win.__bcdLastDownloadName = this.download
        return
      }
      return win.__bcdOriginalAnchorClick?.call(this)
    }
    win.showSaveFilePicker = async (options) => {
      win.__bcdLastDownloadName = options?.suggestedName || ''
      return {
        createWritable: async () => ({
          write: async () => {
            win.__bcdDesktopWriteCount = (win.__bcdDesktopWriteCount || 0) + 1
          },
          close: async () => undefined,
        }),
      }
    }
  })
}

export async function mockFolderPicker(folderName: string, fileName: string, contents: string) {
  await browser.execute((name, childFileName, fileContents) => {
    class E2EFileHandle {
      kind = 'file'
      name: string
      private file: File

      constructor(file: File) {
        this.name = file.name
        this.file = file
      }

      async getFile() {
        return this.file
      }
    }

    class E2EDirectoryHandle {
      kind = 'directory'
      name: string
      private children: Array<[string, E2EDirectoryHandle | E2EFileHandle]> = []

      constructor(directoryName: string) {
        this.name = directoryName
      }

      addFile(file: File) {
        this.children.push([file.name, new E2EFileHandle(file)])
      }

      async *entries() {
        yield* this.children
      }
    }

    const root = new E2EDirectoryHandle(name)
    root.addFile(new File([fileContents], childFileName, { type: 'text/plain' }))
    ;(globalThis as typeof globalThis & {
      showDirectoryPicker?: () => Promise<E2EDirectoryHandle>
    }).showDirectoryPicker = async () => root
  }, folderName, fileName, contents)
}

export async function readCapturedDownloadName() {
  const value = await browser.execute(() => {
    const win = globalThis as typeof globalThis & { __bcdLastDownloadName?: string }
    return win.__bcdLastDownloadName || ''
  })
  return String(value)
}

export async function readCapturedDesktopDownloadName() {
  const value = await browser.execute(() => {
    const win = globalThis as typeof globalThis & { __bcdLastDownloadName?: string }
    return win.__bcdLastDownloadName || ''
  })
  return String(value)
}

export async function readCapturedDesktopWriteCount() {
  const value = await browser.execute(() => {
    const win = globalThis as typeof globalThis & { __bcdDesktopWriteCount?: number }
    return win.__bcdDesktopWriteCount || 0
  })
  return Number(value)
}

export async function readCapturedClipboardText() {
  const value = await browser.execute(() => {
    const win = globalThis as typeof globalThis & { __bcdLastClipboardText?: string }
    return win.__bcdLastClipboardText || ''
  })
  return String(value)
}

async function inputByLabel(label: string) {
  const labelElement = await $(`//label[normalize-space(.)="${label}"]`)
  await labelElement.waitForDisplayed()
  const forId = await labelElement.getAttribute('for')
  if (!forId) throw new Error(`Label "${label}" does not have a for attribute`)
  const input = await $(`#${cssEscape(forId)}`)
  await input.waitForDisplayed()
  return input
}

async function buttonByText(text: string) {
  const button = await $(`//button[normalize-space(.)="${text}"]`)
  await button.waitForClickable()
  return button
}

async function buttonByLabel(label: string) {
  const button = await $(`//button[@aria-label="${label}"]`)
  await button.waitForClickable()
  return button
}

async function linkByText(text: string) {
  const link = await $(`//a[normalize-space(.)="${text}"]`)
  await link.waitForDisplayed()
  return link
}

async function expectTextVisible(text: string) {
  const element = await $(`//*[normalize-space(.)="${text}"]`)
  await element.waitForDisplayed()
}

async function expectButtonNotDisplayed(text: string) {
  await browser.waitUntil(async () => !(await $(`//button[normalize-space(.)="${text}"]`).isDisplayed().catch(() => false)), {
    timeout: 5_000,
    timeoutMsg: `Button "${text}" was still displayed`,
  })
}

function textElementContaining(text: string) {
  return $(`//*[contains(normalize-space(.), "${text}")]`)
}

async function menuItemByText(text: string) {
  const item = await $(`//*[@role="menuitem" and normalize-space(.)="${text}"]`)
  await item.waitForDisplayed()
  return item
}

function fileCard(fileName: string) {
  return $(`[data-testid="${cssString(`file-card-${fileName}`)}"]`)
}

function cssEscape(value: string) {
  return value.replace(/([!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~])/g, '\\$1')
}

function cssString(value: string) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

async function getWindowDiagnostics() {
  const [title, url, source] = await Promise.all([
    browser.getTitle().catch(error => `title error: ${error.message}`),
    browser.getUrl().catch(error => `url error: ${error.message}`),
    browser.getPageSource().catch(error => `source error: ${error.message}`),
  ])
  return [
    'BetterCloudDrive login screen did not become available.',
    `Title: ${title || '<empty>'}`,
    `URL: ${url || '<empty>'}`,
    `Source: ${source.slice(0, 500)}`,
  ].join('\n')
}
