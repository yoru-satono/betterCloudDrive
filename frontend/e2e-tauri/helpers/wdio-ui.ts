import fs from 'node:fs/promises'
import path from 'node:path'
import { $, browser, expect } from '@wdio/globals'

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

export async function loginViaWindow(username: string, password: string) {
  await (await inputByLabel('用户名')).setValue(username)
  await (await inputByLabel('密码')).setValue(password)
  await (await buttonByText('登录')).click()
  await expect(await linkByText('全部文件')).toBeDisplayed()
}

export async function expectDesktopSettings() {
  await expect(await inputByLabel('API 地址')).toHaveValue('http://127.0.0.1:8080/api/v1')
  await expect(await inputByLabel('Web 分享地址')).toHaveValue('http://127.0.0.1:3000')
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
  await card.click({ button: 'right' })
  await expect(await menuItemByText('下载')).toBeDisplayed()
}

export async function createShareFromContextMenu(fileName: string) {
  await openFileContextMenu(fileName)
  await (await menuItemByText('分享')).click()
  await expect(await $('[data-testid="share-modal"]')).toBeDisplayed()
  await (await buttonByText('创建并复制链接')).click()
  await expect(await $('[data-testid="share-modal"]')).not.toBeDisplayed()
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

async function linkByText(text: string) {
  const link = await $(`//a[normalize-space(.)="${text}"]`)
  await link.waitForDisplayed()
  return link
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
