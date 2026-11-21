import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import AdminAnalytics from '@/pages/admin/AdminAnalytics.vue'
import WarehouseTrend from '@/components/admin/WarehouseTrend.vue'
import * as warehouse from '@/api/warehouse'
import * as admin from '@/api/admin'
import { i18n } from '@/i18n'

vi.mock('@/api/warehouse', () => ({ overview: vi.fn(), trends: vi.fn(), teams: vi.fn(), runs: vi.fn() }))
vi.mock('@/api/admin', () => ({ listTeams: vi.fn() }))
enableAutoUnmount(afterEach)

const generation = '15dde341-d5ef-4401-8541-9aac19d01318'
const meta: warehouse.WarehouseMeta = {
  timezone: 'Asia/Shanghai', startDate: '2026-11-19', endDate: '2026-11-20',
  scope: 'ALL', scopeId: 0, generationId: generation, publishedAt: '2026-11-20T00:10:00Z',
  dataThrough: '2026-11-20T00:00:00Z', coverageStart: '2026-11-19', stale: false, status: 'READY',
}
const overview: warehouse.WarehouseOverview = { meta,
  usage: { active_users: 2, active_day: '2026-11-20', uploads: 4, download_attempts: 6 },
  capacity: { current_bytes: 7, trash_bytes: 13, version_bytes: 17, sampled_at: 1795132800000 },
}
const response = <T>(data: T) => ({ data: { code: 200, data } })

beforeEach(() => {
  vi.mocked(warehouse.overview).mockResolvedValue(response(overview) as never)
  vi.mocked(warehouse.trends).mockResolvedValue(response({ meta, days: [] }) as never)
  vi.mocked(warehouse.teams).mockResolvedValue(response({ meta, teams: { records: [], total: 0, page: 1, size: 20, pages: 0 } }) as never)
  vi.mocked(warehouse.runs).mockResolvedValue(response([]) as never)
  vi.mocked(admin.listTeams).mockResolvedValue(response({ records: [{ id: 5, name: 'Fixture team' }], total: 1 }) as never)
})

const render = () => mount(AdminAnalytics, { global: { plugins: [i18n] } })

describe('business analytics', () => {
  it('pins trend and team queries to the overview publication', async () => {
    const wrapper = render()
    await flushPromises()
    expect(warehouse.trends).toHaveBeenCalledWith(expect.objectContaining({ generationId: generation }), expect.any(AbortSignal))
    expect(warehouse.teams).toHaveBeenCalledWith(expect.objectContaining({ generationId: generation }), 1, expect.any(AbortSignal))
    expect(wrapper.find('.headline-metrics').text()).toContain('37 B')
    expect(wrapper.find('.headline-metrics').text()).toContain('2026-11-20')
  })

  it('shows an explicit first-run state rather than fabricated zero metrics', async () => {
    vi.mocked(warehouse.overview).mockResolvedValue(response({ meta: { ...meta, status: 'UNINITIALIZED', generationId: null }, usage: {}, capacity: {} }) as never)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain(i18n.global.t('warehouse.awaitingFirstRun'))
    expect(wrapper.find('.headline-metrics').exists()).toBe(false)
  })

  it('does not present uncollected history as zero bytes or zero activity', async () => {
    vi.mocked(warehouse.overview).mockResolvedValue(response({ meta, usage: {}, capacity: {} }) as never)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('.headline-metrics').text()).toContain(i18n.global.t('warehouse.noData'))
    expect(wrapper.find('.headline-metrics').text()).not.toContain('0 B')
  })

  it('retains the last report during a failed refresh and allows retry', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(warehouse.overview).mockRejectedValueOnce(new Error('controlled unavailable'))
    await wrapper.find('.page-heading button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain(i18n.global.t('warehouse.unavailable'))
    expect(wrapper.find('.headline-metrics').text()).toContain('37 B')
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('applies a team selection without changing authorization or creating user-level queries', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.find('select').setValue('TEAM')
    await wrapper.findAll('select')[1]!.setValue('5')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(warehouse.overview).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'TEAM', teamId: 5 }), expect.any(AbortSignal))
  })

  it('leaves real gaps in capacity history and exposes accessible daily values', () => {
    const wrapper = mount(WarehouseTrend, { props: { days: [
      { day: '2026-11-18', usage: { active_users: 2 }, capacity: null },
      { day: '2026-11-19', usage: null, capacity: null },
      { day: '2026-11-20', usage: { active_users: 0 }, capacity: null },
    ] }, global: { plugins: [i18n] } })
    expect(wrapper.findAll('polyline')).toHaveLength(2)
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    expect(wrapper.findAll('tbody tr')[1]!.text()).toContain(i18n.global.t('warehouse.noData'))
  })
})
