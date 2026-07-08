import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listUsers, createUser, updateUser, resetUserPassword, deleteUser } from '../services/userService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Bad Request',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('userService', () => {
  it('lists users', async () => {
    const users = [{ id: 'u1', username: 'alice', roleIds: ['builtin-admin'], roleNames: ['ADMIN'], enabled: true, createdAt: null, lastLoginAt: null }]
    mockFetch.mockReturnValue(jsonResponse(users))
    expect(await listUsers()).toEqual(users)
    expect(mockFetch).toHaveBeenCalledWith('/api/users')
  })

  it('creates a user with POST', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 'u2', username: 'bob' }))
    await createUser({ username: 'bob', password: 'secret1', roleIds: ['builtin-viewer'] })
    expect(mockFetch).toHaveBeenCalledWith('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'bob', password: 'secret1', roleIds: ['builtin-viewer'] }),
    })
  })

  it('updates role and enabled with PUT', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 'u1' }))
    await updateUser('u1', { roleIds: ['builtin-operator'], enabled: false })
    expect(mockFetch).toHaveBeenCalledWith('/api/users/u1', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ roleIds: ['builtin-operator'], enabled: false }),
    })
  })

  it('resets a password', async () => {
    mockFetch.mockReturnValue(jsonResponse({ success: true }))
    const result = await resetUserPassword('u1', 'newpass1')
    expect(mockFetch).toHaveBeenCalledWith('/api/users/u1/password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: 'newpass1' }),
    })
    expect(result.success).toBe(true)
  })

  it('deletes a user', async () => {
    mockFetch.mockReturnValue(jsonResponse({ success: true }))
    await deleteUser('u1')
    expect(mockFetch).toHaveBeenCalledWith('/api/users/u1', { method: 'DELETE' })
  })

  it('throws the backend message on error', async () => {
    mockFetch.mockReturnValue(jsonResponse({ code: 'FORBIDDEN', message: 'Managing super admin accounts requires system configuration permission.' }, false))
    await expect(deleteUser('u1')).rejects.toThrow('Managing super admin accounts requires system configuration permission.')
  })
})
