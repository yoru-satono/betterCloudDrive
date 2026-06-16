import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { toast } from 'vue-sonner'
import api from '@/api/client'
import { downloadFile, downloadFolderZip, downloadSharedFile, downloadSharedFolderZip } from '@/api/download'
import * as desktopDownload from '@/api/desktopDownload'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

vi.mock('@/api/desktopDownload', () => ({
  downloadDesktopFile: vi.fn(),
  downloadDesktopFolder: vi.fn(),
  isDesktopDownloadRuntime: vi.fn(() => false),
}))

const apiGet = api.get as Mock
const apiPost = api.post as Mock
const toastError = toast.error as Mock
const toastSuccess = toast.success as Mock
const isDesktopDownloadRuntime = desktopDownload.isDesktopDownloadRuntime as Mock
const downloadDesktopFile = desktopDownload.downloadDesktopFile as Mock
const downloadDesktopFolder = desktopDownload.downloadDesktopFolder as Mock

describe('downloadFile', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    toastError.mockReset()
    toastSuccess.mockReset()
    isDesktopDownloadRuntime.mockReturnValue(false)
    downloadDesktopFile.mockReset()
    downloadDesktopFolder.mockReset()
  })

  it('downloads a blob response using the authenticated download endpoint', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    apiPost.mockResolvedValue({ data: { data: { url: '/api/v1/download/7?ticket=abc' } } })

    await downloadFile(7, 'fallback.txt')

    expect(apiPost).toHaveBeenCalledWith('/download/7/ticket', undefined, { suppressToast: true })
    expect(apiGet).not.toHaveBeenCalled()
    expect(click).toHaveBeenCalled()
    expect(toastError).not.toHaveBeenCalled()

    click.mockRestore()
  })

  it('parses a JSON error blob and shows the backend message', async () => {
    apiPost.mockRejectedValue(new Error('Cannot download a folder'))

    await downloadFile(7, 'folder')

    expect(toastError).toHaveBeenCalledWith('Cannot download a folder')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('shows a fallback toast for request failures', async () => {
    apiPost.mockRejectedValue(new Error('network down'))

    await downloadFile(7, 'fallback.txt')

    expect(toastError).toHaveBeenCalledWith('network down')
  })

  it('uses the desktop file save flow in Tauri runtime', async () => {
    isDesktopDownloadRuntime.mockReturnValue(true)
    downloadDesktopFile.mockResolvedValue(true)

    await downloadFile(7, 'fallback.txt')

    expect(downloadDesktopFile).toHaveBeenCalledWith(7, 'fallback.txt')
    expect(apiGet).not.toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalledWith('下载完成')
  })

  it('downloads a folder using the authenticated ZIP endpoint', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    apiPost.mockResolvedValue({ data: { data: { url: '/api/v1/download/folders/7/zip?ticket=abc' } } })

    await downloadFolderZip(7, 'docs')

    expect(apiPost).toHaveBeenCalledWith('/download/folders/7/zip/ticket', undefined, { suppressToast: true })
    expect(click).toHaveBeenCalled()

    click.mockRestore()
  })

  it('uses the desktop recursive folder flow in Tauri runtime', async () => {
    isDesktopDownloadRuntime.mockReturnValue(true)
    downloadDesktopFolder.mockResolvedValue(true)

    await downloadFolderZip(7, 'docs')

    expect(downloadDesktopFolder).toHaveBeenCalledWith({ id: 7, fileName: 'docs' })
    expect(apiGet).not.toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalledWith('文件夹下载完成')
  })

  it('shows folder ZIP limit errors from JSON blob responses', async () => {
    apiPost.mockRejectedValue(new Error('文件夹总大小超过网页下载限制'))

    await downloadFolderZip(7, 'docs')

    expect(toastError).toHaveBeenCalledWith('文件夹总大小超过网页下载限制')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('downloads a public share file using the share download endpoint', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const blob = new Blob(['shared'], { type: 'text/plain' })
    apiPost.mockResolvedValue({
      data: blob,
      headers: {
        'content-type': 'text/plain',
        'content-disposition': 'attachment; filename="shared.txt"',
      },
    })

    await downloadSharedFile('share-code', 9, 'fallback.txt', 'pw')

    expect(apiPost).toHaveBeenCalledWith(
      '/shares/access/share-code/download/9',
      { password: 'pw' },
      { responseType: 'blob', suppressToast: true },
    )
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(click).toHaveBeenCalled()
    expect(toastError).not.toHaveBeenCalled()

    click.mockRestore()
  })

  it('downloads a public share folder using the share ZIP endpoint', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const blob = new Blob(['shared zip'], { type: 'application/zip' })
    apiPost.mockResolvedValue({
      data: blob,
      headers: {
        'content-type': 'application/zip',
        'content-disposition': 'attachment; filename="folder.zip"',
      },
    })

    await downloadSharedFolderZip('share-code', 9, 'folder', 'pw')

    expect(apiPost).toHaveBeenCalledWith(
      '/shares/access/share-code/download/9/zip',
      { password: 'pw' },
      { responseType: 'blob', suppressToast: true },
    )
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(click).toHaveBeenCalled()

    click.mockRestore()
  })
})
