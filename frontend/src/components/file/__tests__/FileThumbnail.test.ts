import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FileThumbnail from '../FileThumbnail.vue'
import type { FileEntity } from '@/types/file'

const apiGet = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', () => ({
  default: {
    get: apiGet,
  },
}))

describe('FileThumbnail', () => {
  beforeEach(() => {
    apiGet.mockReset()
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:thumbnail'),
      revokeObjectURL: vi.fn(),
    })
  })

  it('loads personal thumbnails through the personal endpoint', async () => {
    apiGet.mockResolvedValue({ data: new Blob(['thumb']) })

    const wrapper = mount(FileThumbnail, {
      props: {
        file: imageFile({ id: 11, teamId: null }),
        size: 28,
      },
    })
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith('/files/11/thumbnail', {
      responseType: 'blob',
      suppressToast: true,
    })
    expect(wrapper.find('img').attributes('src')).toBe('blob:thumbnail')
  })

  it('loads team thumbnails through the team endpoint', async () => {
    apiGet.mockResolvedValue({ data: new Blob(['thumb']) })

    mount(FileThumbnail, {
      props: {
        file: imageFile({ id: 12, teamId: 10 }),
        size: 16,
      },
    })
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith('/teams/10/files/12/thumbnail', {
      responseType: 'blob',
      suppressToast: true,
    })
  })

  it('falls back to the file icon before a thumbnail is generated', async () => {
    mount(FileThumbnail, {
      props: {
        file: imageFile({ id: 13, teamId: null, thumbnailPath: null }),
      },
    })
    await flushPromises()

    expect(apiGet).not.toHaveBeenCalled()
  })
})

function imageFile(overrides: Partial<FileEntity>): FileEntity {
  return {
    id: 1,
    userId: 7,
    teamId: null,
    parentId: null,
    fileName: 'photo.png',
    fileType: 'file',
    mimeType: 'image/png',
    fileSize: 100,
    storagePath: 'objects/photo.png',
    thumbnailPath: 'thumbnails/photo.jpg',
    md5Hash: null,
    isDeleted: false,
    versionCount: 1,
    createdAt: '2026-07-09T12:00:00',
    updatedAt: '2026-07-09T12:00:00',
    ...overrides,
  }
}
