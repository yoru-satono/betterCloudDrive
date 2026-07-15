import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { listAllMembers } from '@/api/teams'

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}))

describe('team collection pagination', () => {
  beforeEach(() => vi.mocked(api.get).mockReset())

  it('collects every member page instead of truncating at 100', async () => {
    const first = Array.from({ length: 100 }, (_, index) => ({ userId: index + 1 }))
    const second = Array.from({ length: 50 }, (_, index) => ({ userId: index + 101 }))
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: { data: { records: first, total: 150, page: 1, size: 100, pages: 2 } } })
      .mockResolvedValueOnce({ data: { data: { records: second, total: 150, page: 2, size: 100, pages: 2 } } })

    const members = await listAllMembers(10)

    expect(members).toHaveLength(150)
    expect(api.get).toHaveBeenNthCalledWith(1, '/teams/10/members', { params: { page: 1, size: 100 } })
    expect(api.get).toHaveBeenNthCalledWith(2, '/teams/10/members', { params: { page: 2, size: 100 } })
  })
})
