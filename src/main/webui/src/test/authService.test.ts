import { describe, it, expect, vi, beforeEach } from 'vitest'
import { getAuthStatus, checkSession, login, logout } from '../services/authService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Unauthorized',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('authService', () => {
  // ---- getAuthStatus ----

  describe('getAuthStatus', () => {
    it('returns authEnabled true', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authEnabled: true }))
      const result = await getAuthStatus()
      expect(mockFetch).toHaveBeenCalledWith('/api/auth/status')
      expect(result.authEnabled).toBe(true)
    })

    it('returns authEnabled false', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authEnabled: false }))
      expect((await getAuthStatus()).authEnabled).toBe(false)
    })

    it('returns false on network error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect((await getAuthStatus()).authEnabled).toBe(false)
    })
  })

  // ---- checkSession ----

  describe('checkSession', () => {
    it('returns authenticated true', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authenticated: true }))
      const result = await checkSession()
      expect(mockFetch).toHaveBeenCalledWith('/api/auth/check')
      expect(result.authenticated).toBe(true)
    })

    it('returns authenticated false', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authenticated: false }))
      expect((await checkSession()).authenticated).toBe(false)
    })

    it('returns false on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect((await checkSession()).authenticated).toBe(false)
    })
  })

  // ---- login ----

  describe('login', () => {
    it('sends POST with password', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authenticated: true }))
      const result = await login('secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: 'secret' }),
      })
      expect(result.authenticated).toBe(true)
      expect(result.error).toBeUndefined()
    })

    it('returns error on wrong password', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Unauthorized',
          json: () => Promise.resolve({ code: 'UNAUTHORIZED', message: 'Invalid password.' }),
        } as Response),
      )
      const result = await login('wrong')
      expect(result.authenticated).toBe(false)
      expect(result.error).toBe('Invalid password.')
    })

    it('returns fallback error when body parse fails', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Unauthorized',
          json: () => Promise.reject(new Error('fail')),
        } as Response),
      )
      const result = await login('wrong')
      expect(result.authenticated).toBe(false)
      expect(result.error).toBe('Login failed.')
    })
  })

  // ---- logout ----

  describe('logout', () => {
    it('sends POST to logout', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authenticated: false }))
      await logout()
      expect(mockFetch).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' })
    })

    it('does not throw on network error', async () => {
      mockFetch.mockReturnValue(Promise.reject(new Error('network error')))
      await expect(logout()).resolves.toBeUndefined()
    })
  })
})
