import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listTenants, listTenantsManage, createTenant, updateTenant, deleteTenant } from '../services/tenantService'

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

describe('tenantService', () => {
  it('lists tenant summaries', async () => {
    const tenants = [{ id: 't1', name: 'Support' }]
    mockFetch.mockReturnValue(jsonResponse(tenants))
    expect(await listTenants()).toEqual(tenants)
    expect(mockFetch).toHaveBeenCalledWith('/api/tenants')
  })

  it('lists full tenants for management', async () => {
    const tenants = [{ id: 't1', name: 'Support', description: null, createdAt: null, memberCount: 2 }]
    mockFetch.mockReturnValue(jsonResponse(tenants))
    expect(await listTenantsManage()).toEqual(tenants)
    expect(mockFetch).toHaveBeenCalledWith('/api/tenants/manage')
  })

  it('creates a tenant with POST', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 't2', name: 'Development' }))
    await createTenant({ name: 'Development', description: 'Dev squad' })
    expect(mockFetch).toHaveBeenCalledWith('/api/tenants', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Development', description: 'Dev squad' }),
    })
  })

  it('updates a tenant with PUT', async () => {
    mockFetch.mockReturnValue(jsonResponse({ id: 't1', name: 'Renamed' }))
    await updateTenant('t1', { name: 'Renamed' })
    expect(mockFetch).toHaveBeenCalledWith('/api/tenants/t1', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Renamed' }),
    })
  })

  it('deletes a tenant', async () => {
    mockFetch.mockReturnValue(jsonResponse({ success: true }))
    await deleteTenant('t1')
    expect(mockFetch).toHaveBeenCalledWith('/api/tenants/t1', { method: 'DELETE' })
  })

  it('surfaces backend errors', async () => {
    mockFetch.mockReturnValue(jsonResponse({ error: 'Tenant still has 2 member(s)' }, false))
    await expect(deleteTenant('t1')).rejects.toThrow('Tenant still has 2 member(s)')
  })
})
