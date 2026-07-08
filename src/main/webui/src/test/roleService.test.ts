import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listRoles, getPermissionCatalog, createRole, updateRole, deleteRole } from '../services/roleService'

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

describe('roleService', () => {
  it('lists roles', async () => {
    const roles = [{ id: 'builtin-viewer', name: 'VIEWER', description: null, permissions: ['CONTAINERS_VIEW'], builtIn: true }]
    mockFetch.mockReturnValue(jsonResponse(roles))
    expect(await listRoles()).toEqual(roles)
    expect(mockFetch).toHaveBeenCalledWith('/api/roles')
  })

  it('fetches the permission catalog', async () => {
    const catalog = [{ name: 'CONTAINERS_VIEW', category: 'CONTAINERS' }]
    mockFetch.mockReturnValue(jsonResponse(catalog))
    expect(await getPermissionCatalog()).toEqual(catalog)
    expect(mockFetch).toHaveBeenCalledWith('/api/roles/permissions')
  })

  it('creates a role with POST', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 'r1' }))
    await createRole({ name: 'Deployer', description: 'Deploys', permissions: ['CONTAINERS_RUN'] })
    expect(mockFetch).toHaveBeenCalledWith('/api/roles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Deployer', description: 'Deploys', permissions: ['CONTAINERS_RUN'] }),
    })
  })

  it('updates a role with PUT', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 'r1' }))
    await updateRole('r1', { name: 'Deployer', permissions: ['CONTAINERS_RUN', 'IMAGES_MANAGE'] })
    expect(mockFetch).toHaveBeenCalledWith('/api/roles/r1', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Deployer', permissions: ['CONTAINERS_RUN', 'IMAGES_MANAGE'] }),
    })
  })

  it('deletes a role', async () => {
    mockFetch.mockReturnValue(jsonResponse({ success: true }))
    await deleteRole('r1')
    expect(mockFetch).toHaveBeenCalledWith('/api/roles/r1', { method: 'DELETE' })
  })

  it('throws the backend message on error', async () => {
    mockFetch.mockReturnValue(jsonResponse({ code: 'INVALID_INPUT', message: 'Built-in roles cannot be modified.' }, false))
    await expect(deleteRole('builtin-admin')).rejects.toThrow('Built-in roles cannot be modified.')
  })
})
