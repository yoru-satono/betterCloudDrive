import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
const mock = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('../../web/officeScript', () => ({ loadOfficeScript: mock.load }))
import Editor from '../../web/components/OnlyOfficeEditor.vue'
import { invalidateIdentity } from '../../web/identity'
const config = { documentServerUrl: 'https://office.example', editable: true, config: { document: { key: 'fixture' } } }
const deferred = () => { let resolve!: (v: any) => void; const promise = new Promise<any>(a => { resolve = a }); return { promise, resolve } }
beforeEach(() => mock.load.mockReset())
it.each(['unmount', 'identity'])('does not construct a late editor after %s', async action => {
  const pending = deferred(), ctor = vi.fn(function () { return {} }); mock.load.mockReturnValue(pending.promise)
  const wrapper = mount(Editor, { props: { officeConfig: config } })
  if (action === 'unmount') wrapper.unmount(); else invalidateIdentity('logout')
  pending.resolve(ctor); await flushPromises(); expect(ctor).not.toHaveBeenCalled()
  if (action !== 'unmount') wrapper.unmount()
})
it('destroys the old editor and ignores an earlier script load after config replacement', async () => {
  const pending = deferred(), destroy = vi.fn(), old = vi.fn(function () { return {} }), current = vi.fn(function () { return { destroyEditor: destroy } })
  mock.load.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(current)
  const wrapper = mount(Editor, { props: { officeConfig: config } })
  await wrapper.setProps({ officeConfig: { ...config, config: { document: { key: 'latest' } } } }); await flushPromises()
  pending.resolve(old); await flushPromises(); expect(old).not.toHaveBeenCalled(); expect(current).toHaveBeenCalledTimes(1)
  wrapper.unmount(); expect(destroy).toHaveBeenCalledTimes(1)
})
it('retries a failed load without leaving an old editor alive', async () => {
  const ctor = vi.fn(function () { return {} }); mock.load.mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce(ctor)
  const wrapper = mount(Editor, { props: { officeConfig: config } }); await flushPromises()
  await wrapper.get('button').trigger('click'); await flushPromises(); expect(ctor).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
