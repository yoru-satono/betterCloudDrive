import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { toast } from 'vue-sonner'
import FileBrowserPage from '@/pages/FileBrowserPage.vue'
import { useFilesStore } from '@/stores/files'
import { dispatchFileAction } from '@/components/file/fileActions'
import * as filesApi from '@/api/files'
import * as sharesApi from '@/api/shares'
import { downloadFolderZip, previewFile } from '@/api/download'
import type { FileEntity } from '@/types/file'

const routerPush = vi.fn()

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => ({ params: {} }),
    useRouter: () => ({
      push: async (to: any) => {
        routerPush(to)
        const store = useFilesStore()
        if (to?.name === 'Folder') {
          await store.openDirectory(Number(to.params.folderId))
        } else if (to?.name === 'Files') {
          await store.openDirectory(null)
        }
      },
    }),
  }
})

vi.mock('@/api/files', () => ({
  listFiles: vi.fn(),
  getFile: vi.fn(),
  renameFile: vi.fn(),
  createFolder: vi.fn(),
  deleteFiles: vi.fn(),
  moveFile: vi.fn(),
  copyFile: vi.fn(),
}))

vi.mock('@/api/shares', () => ({
  createShare: vi.fn(),
}))

vi.mock('@/api/favorites', () => ({
  addFavorite: vi.fn(),
}))

vi.mock('@/api/download', () => ({
  downloadFile: vi.fn(),
  downloadFolderZip: vi.fn(),
  previewFile: vi.fn(),
}))

const listFiles = filesApi.listFiles as Mock
const getFile = filesApi.getFile as Mock
const createShare = sharesApi.createShare as Mock
const downloadFolderZipMock = downloadFolderZip as Mock
const previewFileMock = previewFile as Mock

const file = {
  id: 7,
  userId: 1,
  parentId: null,
  fileName: 'plan.txt',
  fileType: 'file',
  mimeType: 'text/plain',
  fileSize: 20,
  storagePath: null,
  md5Hash: null,
  isDeleted: false,
  versionCount: 1,
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-01T00:00:00',
} as const

const stubs = {
  FileToolbar: { template: '<div />' },
  FileBreadcrumb: { template: '<div />' },
  FileGrid: {
    props: ['files'],
    emits: ['file-contextmenu'],
    template: '<button class="file-entry" @click="$emit(\'file-contextmenu\', files[0], $event)">file</button>',
  },
  FileList: { template: '<div />' },
  FileContextMenu: {
    props: ['visible', 'items'],
    emits: ['close'],
    template: '<div v-if="visible"><template v-for="(item, i) in items" :key="i"><button v-if="!item.divider" @click="item.action(); $emit(\'close\')">{{ item.label }}</button></template></div>',
  },
  UploadZone: { template: '<div />' },
  OSpinner: { template: '<div />' },
  OModal: { template: '<div v-if="open"><slot /><slot name="footer" /></div>', props: ['open'] },
  OInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :aria-label="$attrs.label" :value="modelValue" @input="onInput" />',
    setup(_props: unknown, { emit }: { emit: (event: string, value: string) => void }) {
      return {
        onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
      }
    },
  },
  OButton: { template: '<button @click="$emit(\'click\', $event)"><slot /></button>' },
}

beforeEach(() => {
  setActivePinia(createPinia())
  listFiles.mockResolvedValue({ data: { data: { records: [file], total: 1, page: 1, pages: 1 } } })
  getFile.mockResolvedValue({ data: { data: file } })
  createShare.mockResolvedValue({ data: { data: { shareCode: 'share123' } } })
  downloadFolderZipMock.mockReset()
  previewFileMock.mockReset()
  previewFileMock.mockResolvedValue({ data: new Blob(['preview']) })
  routerPush.mockReset()
  Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
  vi.stubGlobal('crypto', {
    getRandomValues: vi.fn((array: Uint32Array) => {
      array.forEach((_, index) => { array[index] = index })
      return array
    }),
  })
})

describe('FileBrowserPage share dialog', () => {
  async function openShareDialog() {
    const store = useFilesStore()
    store.viewMode = 'grid'
    const wrapper = mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    await wrapper.find('.file-entry').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '分享')!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('creates a share with maxVisits from the dialog', async () => {
    const wrapper = await openShareDialog()

    await wrapper.find('input[aria-label="访问次数限制"]').setValue('12')
    await wrapper.findAll('button').find(button => button.text() === '创建并复制链接')!.trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith({ fileId: 7, maxVisits: 12, password: undefined })
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('/s/share123'))
    expect(toast.success).toHaveBeenCalledWith('分享链接已复制到剪贴板')
  })

  it('creates a protected share with a manual password', async () => {
    const wrapper = await openShareDialog()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('input[aria-label="访问密码"]').setValue('abcd')
    await wrapper.findAll('button').find(button => button.text() === '创建并复制链接')!.trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith({ fileId: 7, maxVisits: undefined, password: 'abcd' })
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('http://localhost:3000/s/share123')
    expect(toast.success).toHaveBeenCalledWith('分享链接已复制到剪贴板')
  })

  it('creates a protected share with an automatically generated four-character password', async () => {
    const wrapper = await openShareDialog()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '自动生成')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '创建并复制链接')!.trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith({ fileId: 7, maxVisits: undefined, password: 'ABCD' })
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('http://localhost:3000/s/share123')
  })

  it('supports automatically generated eight-character passwords', async () => {
    const wrapper = await openShareDialog()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '自动生成')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '8 位')!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '创建并复制链接')!.trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith({ fileId: 7, maxVisits: undefined, password: 'ABCDEFGH' })
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('http://localhost:3000/s/share123')
  })

  it('rejects manual passwords outside the allowed length', async () => {
    const wrapper = await openShareDialog()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('input[aria-label="访问密码"]').setValue('abc')
    await wrapper.findAll('button').find(button => button.text() === '创建并复制链接')!.trigger('click')
    await flushPromises()

    expect(createShare).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('分享密码长度必须为 4-16 位')
  })

  it('downloads folders from the context menu as ZIP files', async () => {
    const folder = {
      ...file,
      id: 8,
      fileName: 'docs',
      fileType: 'folder',
      mimeType: null,
      fileSize: 0,
    }
    listFiles.mockResolvedValueOnce({ data: { data: { records: [folder], total: 1, page: 1, pages: 1 } } })
    const store = useFilesStore()
    store.viewMode = 'grid'
    const wrapper = mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    await wrapper.find('.file-entry').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '下载')!.trigger('click')

    expect(downloadFolderZipMock).toHaveBeenCalledWith(8, 'docs')
  })

  it('handles search result file preview actions', async () => {
    const store = useFilesStore()
    store.viewMode = 'grid'
    mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    dispatchFileAction({ action: 'preview', file })
    await flushPromises()

    expect(previewFileMock).toHaveBeenCalledWith(7)
  })

  it('handles search result folder navigation actions', async () => {
    const folder: FileEntity = {
      ...file,
      id: 9,
      fileName: 'docs',
      fileType: 'folder',
      mimeType: null,
      fileSize: 0,
    }
    const store = useFilesStore()
    store.viewMode = 'grid'
    getFile.mockImplementation(async (id: number) => ({ data: { data: id === 9 ? folder : file } }))
    listFiles.mockImplementation(async () => ({ data: { data: { records: [folder], total: 1, page: 1, pages: 1 } } }))
    mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    dispatchFileAction({ action: 'enter-folder', file: folder })
    await flushPromises()

    expect(store.currentParentId).toBe(9)
    expect(store.breadcrumb.at(-1)).toEqual({ id: 9, name: 'docs' })
  })

  it('adds open location as the first item for search context menus', async () => {
    const store = useFilesStore()
    store.viewMode = 'grid'
    const wrapper = mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    dispatchFileAction({
      action: 'context-menu',
      file,
      event: new MouseEvent('contextmenu', { clientX: 10, clientY: 10, bubbles: true }),
    })
    await flushPromises()

    const labels = wrapper.findAll('button').map(button => button.text())
    expect(labels).toContain('打开文件所在位置')
    expect(labels.indexOf('打开文件所在位置')).toBeLessThan(labels.indexOf('下载'))
  })

  it('opens a search result location through router navigation', async () => {
    const locatedFile: FileEntity = { ...file, parentId: 42 }
    const parentFolder: FileEntity = {
      ...file,
      id: 42,
      fileName: 'parent',
      fileType: 'folder',
      parentId: null,
      mimeType: null,
      fileSize: 0,
    }
    const store = useFilesStore()
    store.viewMode = 'grid'
    getFile.mockImplementation(async (id: number) => ({ data: { data: id === 42 ? parentFolder : locatedFile } }))
    listFiles.mockImplementation(async () => ({ data: { data: { records: [locatedFile], total: 1, page: 1, pages: 1 } } }))
    const wrapper = mount(FileBrowserPage, { global: { stubs } })
    await flushPromises()

    dispatchFileAction({
      action: 'context-menu',
      file: locatedFile,
      event: new MouseEvent('contextmenu', { clientX: 10, clientY: 10, bubbles: true }),
    })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '打开文件所在位置')!.trigger('click')
    await flushPromises()

    expect(routerPush).toHaveBeenCalledWith({ name: 'Folder', params: { folderId: 42 } })
    expect(store.currentParentId).toBe(42)
    expect(store.breadcrumb.at(-1)).toEqual({ id: 42, name: 'parent' })
  })
})
