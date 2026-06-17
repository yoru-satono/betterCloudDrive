import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import AdminLogs from '@/pages/admin/AdminLogs.vue'
import * as adminApi from '@/api/admin'

vi.mock('@/api/admin', () => ({
  listLogs: vi.fn(),
  listSystemLogs: vi.fn(),
  createGrafanaSession: vi.fn(),
}))

const listLogs = adminApi.listLogs as Mock
const listSystemLogs = adminApi.listSystemLogs as Mock
const createGrafanaSession = adminApi.createGrafanaSession as Mock

const stubs = {
  OButton: { template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  OInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  OSpinner: { template: '<span>loading</span>' },
  OEmptyState: { props: ['title'], template: '<div>{{ title }}</div>' },
}

beforeEach(() => {
  listLogs.mockResolvedValue({ data: { data: { records: [], total: 0 } } })
  listSystemLogs.mockResolvedValue({
    data: {
      data: [{
        id: 'trace-1',
        idType: 'traceId',
        traceId: 'trace-1',
        requestId: 'req-1',
        timestamp: '2026-06-17T08:00:00Z',
        level: 'INFO',
        logger: 'com.betterclouddrive.Demo',
        message: 'request handled',
        path: '/api/v1/files',
        method: 'GET',
        logType: 'runtime',
        grafanaUrl: '/grafana/explore?left=trace-1',
      }],
    },
  })
  createGrafanaSession.mockResolvedValue({ data: { data: null } })
})

describe('AdminLogs', () => {
  it('creates a Grafana admin session before opening a system log link', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    createGrafanaSession.mockImplementation(async () => {
      return { data: { data: null } }
    })

    const wrapper = mount(AdminLogs, { global: { stubs } })
    await flushPromises()

    const systemTab = wrapper.findAll('button').find((button) => button.text() === '系统日志')
    expect(systemTab).toBeTruthy()
    await systemTab!.trigger('click')
    await flushPromises()

    await wrapper.find('button.grafana-link').trigger('click')
    await flushPromises()

    expect(createGrafanaSession).toHaveBeenCalledTimes(1)
    expect(openSpy).toHaveBeenCalledWith('/grafana/explore?left=trace-1', '_blank', 'noopener,noreferrer')
  })
})
