import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia, disposePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import FilePreviewModal from '@/components/file/FilePreviewModal.vue'
import { usePreviewStore } from '@/stores/preview'
import { invalidateIdentity } from '@/platform/identity'
import * as versionsApi from '@/api/versions'
import { setLocale } from '@/i18n'

const confirmation = vi.hoisted(() => vi.fn())
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => ({ confirm: confirmation }) }))
vi.mock('@/api/versions', () => ({ listTeamVersions: vi.fn(), downloadTeamVersion: vi.fn(), restoreTeamVersion: vi.fn() }))
vi.mock('@/api/download', () => ({ previewFile: vi.fn(), downloadFile: vi.fn(), downloadTeamFile: vi.fn() }))

const deferred = () => {
  let resolve!: (value: any) => void, reject!: (value: any) => void
  const promise = new Promise<any>((a,b) => { resolve=a; reject=b })
  return { promise, resolve, reject }
}
const version = (n: number) => ({ data: { data: [{ id:n,versionNumber:n,fileSize:8,createdAt:'2026-11-25T00:00:00Z' }] } })
let pinia: ReturnType<typeof createPinia>, wrapper: ReturnType<typeof mount>, preview: ReturnType<typeof usePreviewStore>
const select = (id: number) => Object.assign(preview, { open:true,teamId:8,target:{id,fileName:`${id}.docx`},officeMode:true,officeConfig:{editable:true},loading:false })
const button = (text: string) => wrapper.findAll('button').find(b => b.text()===text)!
const row = (n: number) => wrapper.find(`[data-testid="office-version-row-${n}"]`)
beforeEach(async () => {
  pinia=createPinia();setActivePinia(pinia);await setLocale('zh-Hans')
  vi.mocked(versionsApi.listTeamVersions).mockReset()
  vi.mocked(versionsApi.restoreTeamVersion).mockReset()
  confirmation.mockReset()
  preview=usePreviewStore();select(101)
  wrapper=mount(FilePreviewModal,{global:{stubs:{OModal:{props:['open'],template:'<section v-if="open"><slot/><footer><slot name="footer"/></footer></section>'},OnlyOfficeEditor:true}}})
})
afterEach(() => { wrapper.unmount();disposePinia(pinia) })

it.each(['resolve','reject'])('discards old %s while the new target is loading', async outcome => {
  const a=deferred(),b=deferred()
  vi.mocked(versionsApi.listTeamVersions).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  await button('历史版本').trigger('click')
  select(202);await flushPromises();await button('历史版本').trigger('click')
  if(outcome==='resolve')a.resolve(version(7));else a.reject(new Error('old'))
  await flushPromises()
  expect(row(7).exists()).toBe(false)
  expect(wrapper.find('.office-versions__loading').exists()).toBe(true)
  expect(wrapper.find('.office-versions__empty').exists()).toBe(false)
  b.resolve(version(2));await flushPromises()
  expect(wrapper.find('.office-versions__target').text()).toBe('202.docx')
  await row(2).findAll('button').find(b=>b.text()==='下载')!.trigger('click')
  expect(versionsApi.downloadTeamVersion).toHaveBeenCalledWith(8,202,2,'202.docx')
})

it('keeps the newest list when an older query for the same target finishes last', async () => {
  const a=deferred(),b=deferred()
  vi.mocked(versionsApi.listTeamVersions).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  await button('历史版本').trigger('click');await button('历史版本').trigger('click')
  b.resolve(version(2));await flushPromises();a.resolve(version(7));await flushPromises()
  expect(row(2).exists()).toBe(true);expect(row(7).exists()).toBe(false)
})

it.each(['close','logout','unmount'])('invalidates pending lists on %s', async action => {
  const a=deferred();vi.mocked(versionsApi.listTeamVersions).mockReturnValueOnce(a.promise)
  await button('历史版本').trigger('click')
  if(action==='close')await wrapper.findAll('button').filter(b=>b.text()==='关闭').at(-1)!.trigger('click')
  else if(action==='logout')invalidateIdentity('logout')
  else wrapper.unmount()
  a.resolve(version(7));await flushPromises()
  expect(row(7).exists()).toBe(false)
  if(action==='close') {
    vi.mocked(versionsApi.listTeamVersions).mockResolvedValueOnce(version(2) as never)
    await button('历史版本').trigger('click');await flushPromises()
    expect(row(2).exists()).toBe(true)
  }
})

it('does not restore after its confirmation target changes', async () => {
  vi.mocked(versionsApi.listTeamVersions).mockResolvedValueOnce(version(7) as never)
  await button('历史版本').trigger('click');await flushPromises()
  const confirm=deferred();confirmation.mockReturnValueOnce(confirm.promise)
  await row(7).findAll('button').find(b=>b.text()==='恢复')!.trigger('click')
  select(202);confirm.resolve(true);await flushPromises()
  expect(versionsApi.restoreTeamVersion).not.toHaveBeenCalled()
})

it('does not replace or refresh the new target with a late restore response', async () => {
  vi.mocked(versionsApi.listTeamVersions).mockResolvedValueOnce(version(7) as never)
  await button('历史版本').trigger('click');await flushPromises()
  const response=deferred();confirmation.mockResolvedValueOnce(true)
  vi.mocked(versionsApi.restoreTeamVersion).mockReturnValueOnce(response.promise)
  const refresh=vi.spyOn(preview,'refreshOfficeConfig')
  await row(7).findAll('button').find(b=>b.text()==='恢复')!.trigger('click');await flushPromises()
  expect(versionsApi.restoreTeamVersion).toHaveBeenCalledWith(8,101,7)
  select(202);response.resolve({data:{data:{id:101,fileName:'101.docx'}}});await flushPromises()
  expect(preview.target?.id).toBe(202);expect(refresh).not.toHaveBeenCalled();expect(toast.success).not.toHaveBeenCalled()
})

it('refreshes the same file and its versions after a valid restore', async () => {
  vi.mocked(versionsApi.listTeamVersions).mockResolvedValueOnce(version(7) as never).mockResolvedValueOnce(version(8) as never)
  await button('历史版本').trigger('click');await flushPromises()
  confirmation.mockResolvedValueOnce(true)
  vi.mocked(versionsApi.restoreTeamVersion).mockResolvedValueOnce({data:{data:{id:101,fileName:'101.docx'}}} as never)
  const refresh=vi.spyOn(preview,'refreshOfficeConfig').mockResolvedValueOnce(undefined)
  await row(7).findAll('button').find(b=>b.text()==='恢复')!.trigger('click');await flushPromises()
  expect(refresh).toHaveBeenCalledTimes(1);expect(row(8).exists()).toBe(true)
})
