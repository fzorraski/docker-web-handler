import { describe, it, expect, vi, beforeEach } from 'vitest'
import fetchWithAuth, { ForbiddenError, RateLimitError } from '../services/fetchWithAuth'

const mockFetch = vi.fn()
global.fetch = mockFetch

function response(status: number, data: unknown = {}) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('fetchWithAuth', () => {
  it('returns the response on success', async () => {
    mockFetch.mockReturnValue(response(200, { ok: true }))
    const res = await fetchWithAuth('/api/test')
    expect(res.status).toBe(200)
  })

  it('dispatches auth:session-expired on 401', async () => {
    const listener = vi.fn()
    window.addEventListener('auth:session-expired', listener)
    mockFetch.mockReturnValue(response(401))
    await fetchWithAuth('/api/test')
    expect(listener).toHaveBeenCalled()
    window.removeEventListener('auth:session-expired', listener)
  })

  it('throws ForbiddenError and dispatches auth:forbidden on RBAC 403', async () => {
    const listener = vi.fn()
    window.addEventListener('auth:forbidden', listener)
    mockFetch.mockReturnValue(response(403, { code: 'FORBIDDEN', message: 'Access denied: missing permission.' }))
    await expect(fetchWithAuth('/api/test')).rejects.toThrow(ForbiddenError)
    expect(listener).toHaveBeenCalled()
    window.removeEventListener('auth:forbidden', listener)
  })

  it('passes non-RBAC 403 responses through untouched', async () => {
    const listener = vi.fn()
    window.addEventListener('auth:forbidden', listener)
    mockFetch.mockReturnValue(response(403, { message: 'Invalid operations password.' }))
    const res = await fetchWithAuth('/api/test')
    expect(res.status).toBe(403)
    expect(await res.json()).toEqual({ message: 'Invalid operations password.' })
    expect(listener).not.toHaveBeenCalled()
    window.removeEventListener('auth:forbidden', listener)
  })

  it('throws RateLimitError with retryAfter on 429', async () => {
    mockFetch.mockReturnValue(response(429, { message: 'Too many attempts.', retryAfter: 30 }))
    const error = await fetchWithAuth('/api/test').catch(e => e)
    expect(error).toBeInstanceOf(RateLimitError)
    expect(error.retryAfter).toBe(30)
  })
})
