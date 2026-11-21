import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import AdminStorageInspections from '@/pages/admin/AdminStorageInspections.vue'
import * as api from '@/api/storage-inspections'
import { i18n } from '@/i18n'

vi.mock('@/api/storage-inspections', async (original) => ({
  ...await original<typeof import('@/api/storage-inspections')>(),
  overview: vi.fn(), findings: vi.fn(), runs: vi.fn(), detail: vi.fn(), start: vi.fn(), cancel: vi.fn(),
  recheck: vi.fn(), review: vi.fn(), preview: vi.fn(), repair: vi.fn(),
}))
enableAutoUnmount(afterEach)
const id = '9285d018-c913-4320-8003-2254d83384f2'
const response = <T>(data: T) => ({ data: { code: 200, data } })
const run: api.InspectionRun = {
  id, kind: 'FULL', findingId: null, status: 'SUCCEEDED', phase: 'REVISIT', ruleVersion: 1, requestedBy: 1,
  createdAt: '2026-11-20T00:00:00Z', startedAt: '2026-11-20T00:00:00Z', finishedAt: '2026-11-20T00:01:00Z',
  metadataAt: '2026-11-20T00:00:00Z', scanStartedAt: '2026-11-20T00:00:00Z', scanFinishedAt: '2026-11-20T00:00:50Z',
  metadataComplete: true, inventoryComplete: true, cancelRequested: false, objectsScanned: 11, objectBytes: 110,
  referencesScanned: 14, candidatesChecked: 1, errorKey: null,
}
const finding: api.InspectionFinding = {
  id, type: 'QUOTA_DRIFT', referenceKind: 'QUOTA', referenceId: '7', objectFingerprint: null, scope: 'PERSONAL', ownerId: 7,
  severity: 'WARNING', status: 'CONFIRMED', reviewStatus: 'OPEN', firstSeenAt: run.createdAt, stableSince: run.createdAt,
  lastObservedAt: run.finishedAt!, observations: 2, expectedBytes: 40, observedBytes: 10, objectModifiedAt: null,
  lastSeenRun: id, resolvedAt: null,
}
const overview: api.InspectionOverview = { enabled: true, accountingReady: true, scheduled: true, zone: 'Asia/Shanghai',
  nextScheduledAt: '2026-11-20T18:15:00Z', latestRun: run, latestComplete: run,
  counts: [{ type: 'QUOTA_DRIFT', status: 'CONFIRMED', severity: 'WARNING', count: 1 }],
}
const detail: api.FindingDetail = { finding, objectKey: null, reviews: [], repairs: [] }
const render = () => mount(AdminStorageInspections, { global: { plugins: [i18n] } })
const button = (wrapper: ReturnType<typeof render>, key: string) => {
  const found = wrapper.findAll('button').find((item) => item.text() === i18n.global.t(key))
  expect(found).toBeDefined()
  return found!
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false } })
  vi.mocked(api.overview).mockResolvedValue(response(overview) as never)
  vi.mocked(api.findings).mockResolvedValue(response({ records: [finding], total: 1, page: 1, size: 20, pages: 1 }) as never)
  vi.mocked(api.runs).mockResolvedValue(response({ records: [run], total: 1, page: 1, size: 5, pages: 1 }) as never)
  vi.mocked(api.detail).mockResolvedValue(response(detail) as never)
  vi.mocked(api.preview).mockResolvedValue(response({ id, findingId: id, actorId: 1, scope: 'PERSONAL', ownerId: 7,
    recordedBytes: 10, expectedBytes: 40, quotaRevision: 2, createdAt: run.createdAt, expiresAt: '2026-11-20T00:10:00Z' }) as never)
})

describe('storage inspections', () => {
  it('explains known run failures without exposing arbitrary backend details', async () => {
    vi.mocked(api.runs).mockResolvedValue(response({ records: [
      { ...run, status: 'FAILED', errorKey: 'inventory_head_unavailable' },
      { ...run, id: 'other-run', status: 'FAILED', errorKey: 'private-backend-detail' },
    ], total: 2, page: 1, size: 5, pages: 1 }) as never)
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('.inspection-history').text()).toContain(i18n.global.t('inspection.runError.inventory_head_unavailable'))
    expect(wrapper.find('.inspection-history').text()).toContain(i18n.global.t('inspection.runIncomplete'))
    expect(wrapper.text()).not.toContain('private-backend-detail')
  })

  it('keeps full coverage separate from a newer partial scan', async () => {
    vi.mocked(api.overview).mockResolvedValue(response({ ...overview, latestRun: { ...run, status: 'PARTIAL', objectsScanned: 3, inventoryComplete: false } }) as never)
    const wrapper = render(); await flushPromises()
    expect(wrapper.findAll('.inspection-summary strong').at(-1)?.text()).toBe('11')
    expect(wrapper.find('.inspection-summary').text()).toContain(i18n.global.t('inspection.lastComplete'))
  })

  it('does not turn the absence of a complete report into zero object bytes', async () => {
    vi.mocked(api.overview).mockResolvedValue(response({ ...overview, latestRun: null, latestComplete: null, counts: [] }) as never)
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('.inspection-summary').text()).toContain(i18n.global.t('inspection.noComplete'))
    expect(wrapper.find('.inspection-summary').text()).not.toContain('0 B')
  })

  it('retains results and marks them as old when refresh fails', async () => {
    const wrapper = render(); await flushPromises()
    vi.mocked(api.overview).mockRejectedValueOnce(new Error('fixture unavailable'))
    await button(wrapper, 'inspection.refresh').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(i18n.global.t('inspection.refreshFailed'))
    expect(wrapper.findAll('.inspection-summary strong').at(-1)?.text()).toBe('11')
    expect(wrapper.findAll('tbody tr')).not.toHaveLength(0)
  })

  it('disables new scans until accounting cutover is ready', async () => {
    vi.mocked(api.overview).mockResolvedValue(response({ ...overview, accountingReady: false }) as never)
    const wrapper = render(); await flushPromises()
    expect(button(wrapper, 'inspection.start').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain(i18n.global.t('inspection.cutoverRequired'))
    expect(api.start).not.toHaveBeenCalled()
  })

  it('cannot reopen details after a late response arrives for a closed selection', async () => {
    let resolve!: (value: never) => void
    vi.mocked(api.detail).mockReturnValueOnce(new Promise((done) => { resolve = done }) as never)
    const wrapper = render(); await flushPromises()
    await button(wrapper, 'inspection.details').trigger('click')
    await wrapper.get('.inspection-detail button').trigger('click')
    resolve(response(detail) as never); await flushPromises()
    expect(wrapper.get('.inspection-detail').text()).toContain(i18n.global.t('inspection.selectFinding'))
    expect(wrapper.find('.inspection-review-form').exists()).toBe(false)
  })

  it('requires a selected space before filtering by owner ID', async () => {
    const wrapper = render(); await flushPromises()
    await wrapper.get('.inspection-owner input').setValue('7')
    await wrapper.get('.inspection-filters').trigger('submit')
    expect(api.findings).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain(i18n.global.t('error.inspectionInvalidFilter'))
  })

  it('requires a reason and confirmation, and reuses the idempotency key after an uncertain repair response', async () => {
    vi.mocked(api.repair).mockRejectedValueOnce(new Error('fixture transport interrupted')).mockResolvedValueOnce(response({}) as never)
    const wrapper = render(); await flushPromises()
    await button(wrapper, 'inspection.details').trigger('click'); await flushPromises()
    await button(wrapper, 'inspection.previewRepair').trigger('click'); await flushPromises()
    expect(button(wrapper, 'inspection.applyRepair').attributes('disabled')).toBeDefined()
    await wrapper.get('dialog textarea').setValue('Controlled fixture repair')
    await wrapper.get('dialog input[type="checkbox"]').setValue(true)
    await wrapper.get('dialog form').trigger('submit'); await flushPromises()
    expect(api.repair).toHaveBeenCalledTimes(1)
    expect(wrapper.get('dialog textarea').attributes('disabled')).toBeDefined()
    await wrapper.get('dialog form').trigger('submit'); await flushPromises()
    expect(api.repair).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.repair).mock.calls[1]![0]).toEqual(vi.mocked(api.repair).mock.calls[0]![0])
    expect(vi.mocked(api.repair).mock.calls[0]![0]).toMatchObject({ previewId: id, reason: 'Controlled fixture repair' })
  })
})
