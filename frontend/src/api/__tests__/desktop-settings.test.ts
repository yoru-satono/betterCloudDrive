import { describe, expect, it, vi, beforeEach } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  bytesToMbps,
  chooseDefaultDownloadDirectory,
  DEFAULT_DESKTOP_SETTINGS,
  getDesktopSettings,
  mbpsToBytes,
  saveDesktopSettings,
} from '@/api/desktopSettings'

vi.mock('@/config/runtime', () => ({
  isDesktopRuntime: () => true,
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}))

const invokeMock = vi.mocked(invoke)

describe('desktop settings api', () => {
  beforeEach(() => {
    invokeMock.mockReset()
  })

  it('loads and saves settings through Tauri commands', async () => {
    invokeMock.mockResolvedValueOnce(DEFAULT_DESKTOP_SETTINGS)

    await expect(getDesktopSettings()).resolves.toEqual(DEFAULT_DESKTOP_SETTINGS)
    expect(invokeMock).toHaveBeenCalledWith('get_desktop_settings')

    invokeMock.mockResolvedValueOnce({ ...DEFAULT_DESKTOP_SETTINGS, maxConcurrentUploads: 5 })
    await expect(saveDesktopSettings({ ...DEFAULT_DESKTOP_SETTINGS, maxConcurrentUploads: 5 }))
      .resolves.toMatchObject({ maxConcurrentUploads: 5 })
    expect(invokeMock).toHaveBeenCalledWith('save_desktop_settings', {
      settings: { ...DEFAULT_DESKTOP_SETTINGS, maxConcurrentUploads: 5 },
    })
  })

  it('chooses a default download directory', async () => {
    invokeMock.mockResolvedValue('D:\\Downloads')

    await expect(chooseDefaultDownloadDirectory()).resolves.toBe('D:\\Downloads')
    expect(invokeMock).toHaveBeenCalledWith('choose_default_download_directory')
  })

  it('converts speed limits between bytes and MB/s strings', () => {
    expect(bytesToMbps(null)).toBe('')
    expect(bytesToMbps(10 * 1024 * 1024)).toBe('10')
    expect(mbpsToBytes('')).toBeNull()
    expect(mbpsToBytes('1.5')).toBe(1572864)
  })
})
