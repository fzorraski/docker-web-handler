import { describe, it, expect, vi, beforeEach } from 'vitest'
import { activityOverview, activityByUser } from '../services/activityService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Bad Request',
    json: () => Promise.resolve(data),
  } as Response)
}

/** The first argument the service passed to fetch. */
function requestedUrl(): string {
  return mockFetch.mock.calls[0][0] as string
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('activityService', () => {
  it('requests the overview for a range', async () => {
    mockFetch.mockReturnValue(jsonResponse({ from: '2026-08-01', to: '2026-08-10' }))

    const overview = await activityOverview({ from: '2026-08-01', to: '2026-08-10' })

    expect(overview.from).toBe('2026-08-01')
    expect(requestedUrl()).toBe('/api/reports/activity/overview?from=2026-08-01&to=2026-08-10')
  })

  it('sends the tenant filter only when one is chosen', async () => {
    mockFetch.mockReturnValue(jsonResponse({}))
    await activityOverview({ from: '2026-08-01', tenant: 't1' })
    expect(requestedUrl()).toContain('tenant=t1')

    mockFetch.mockReset()
    mockFetch.mockReturnValue(jsonResponse({}))
    await activityOverview({ from: '2026-08-01', tenant: '' })
    expect(requestedUrl()).not.toContain('tenant')
  })

  it('omits empty range filters instead of sending blanks', async () => {
    mockFetch.mockReturnValue(jsonResponse({}))

    await activityOverview({})

    // a blank from/to must not reach the API as an unparseable empty string
    expect(requestedUrl()).toBe('/api/reports/activity/overview?')
  })

  it('still pages the flat rows for the details table', async () => {
    mockFetch.mockReturnValue(jsonResponse({ rows: [], total: 0, page: 0, size: 50 }))

    await activityByUser(2, 50, { from: '2026-08-01', action: 'LOGIN' })

    expect(requestedUrl()).toBe(
      '/api/reports/activity/by-user?page=2&size=50&from=2026-08-01&action=LOGIN')
  })
})
