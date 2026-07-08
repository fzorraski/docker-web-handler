import { describe, it, expect, vi, beforeEach } from 'vitest'
import { getAuthStatus, checkSession, getMe, changeOwnPassword, login, logout } from '../services/authService'

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

    it('returns rbacEnabled from the backend', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authEnabled: true, rbacEnabled: true }))
      const result = await getAuthStatus()
      expect(result.rbacEnabled).toBe(true)
    })

    it('returns false on network error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      const result = await getAuthStatus()
      expect(result.authEnabled).toBe(false)
      expect(result.rbacEnabled).toBe(false)
    })
  })

  // ---- getMe ----

  describe('getMe', () => {
    it('returns the current user with permissions', async () => {
      mockFetch.mockReturnValue(jsonResponse({
        rbac: true,
        username: 'alice',
        roleIds: ['builtin-operator'],
        roleNames: ['OPERATOR'],
        permissions: ['CONTAINERS_VIEW', 'CONTAINERS_RUN'],
      }))
      const user = await getMe()
      expect(mockFetch).toHaveBeenCalledWith('/api/auth/me')
      expect(user).toEqual({
        username: 'alice',
        roleIds: ['builtin-operator'],
        roleNames: ['OPERATOR'],
        permissions: ['CONTAINERS_VIEW', 'CONTAINERS_RUN'],
      })
    })

    it('returns null in legacy (non-rbac) mode', async () => {
      mockFetch.mockReturnValue(jsonResponse({ rbac: false, authenticated: true }))
      expect(await getMe()).toBeNull()
    })

    it('returns null on error status', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) } as Response),
      )
      expect(await getMe()).toBeNull()
    })
  })

  // ---- changeOwnPassword ----

  describe('changeOwnPassword', () => {
    it('sends both passwords and reports success', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await changeOwnPassword('old-pw', 'new-pw')
      expect(mockFetch).toHaveBeenCalledWith('/api/auth/change-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword: 'old-pw', newPassword: 'new-pw' }),
      })
      expect(result.success).toBe(true)
    })

    it('returns backend error message on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          status: 400,
          json: () => Promise.resolve({ code: 'INVALID_INPUT', message: 'Current password is incorrect.' }),
        } as Response),
      )
      const result = await changeOwnPassword('bad', 'new-pw')
      expect(result.success).toBe(false)
      expect(result.error).toBe('Current password is incorrect.')
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

    it('includes username in body when provided (RBAC mode)', async () => {
      mockFetch.mockReturnValue(jsonResponse({ authenticated: true }))
      await login('secret', 'alice')

      expect(mockFetch).toHaveBeenCalledWith('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'alice', password: 'secret' }),
      })
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
