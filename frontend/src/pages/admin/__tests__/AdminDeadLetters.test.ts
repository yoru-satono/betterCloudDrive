import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import AdminDeadLetters from '@/pages/admin/AdminDeadLetters.vue'
import * as adminApi from '@/api/admin'

vi.mock('@/api/admin', () => ({
  listDeadLetters: vi.fn(),
  replayDeadLetters: vi.fn(),
  discardDeadLetter: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))

const listDeadLetters = adminApi.listDeadLetters as Mock
const replayDeadLetters = adminApi.replayDeadLetters as Mock

const record = {
  id: '11111111-1111-1111-1111-111111111111',
  originalQueue: 'team-tasks',
  taskType: 'THUMBNAIL',
  failureCategory: 'delivery-exhausted',
  deliveryCount: 5,
  firstFailedAt: '2026-07-26T10:00:00',
  lastFailedAt: '2026-07-26T10:05:00',
  requestId: 'request-1',
  traceId: '0123456789abcdef0123456789abcdef',
  status: 'OPEN',
  resolvedAt: null,
}

const stubs = {
  OButton: { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  OSpinner: { template: '<span>loading</span>' },
  OEmptyState: { props: ['title'], template: '<div>{{ title }}</div>' },
}

beforeEach(() => {
  listDeadLetters.mockResolvedValue({ data: { data: { records: [record], total: 1 } } })
  replayDeadLetters.mockResolvedValue({ data: { data: [{ ...record, status: 'REPLAYED' }] } })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('AdminDeadLetters', () => {
  it('shows bounded metadata and replays a selected record once', async () => {
    const wrapper = mount(AdminDeadLetters, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('team-tasks')
    expect(wrapper.text()).toContain('delivery-exhausted')

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button')[0].trigger('click')
    await flushPromises()

    expect(replayDeadLetters).toHaveBeenCalledWith([record.id])
  })
})
