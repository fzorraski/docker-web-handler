import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/tenants'

// the admin page surfaces backend messages itself (e.g. delete-blocked-by-members),
// so the global forbidden toast is suppressed to avoid double notifications
const OPTS = { forbiddenEvent: false }

/** Minimal view for selectors - available to any authenticated user. */
export interface TenantSummary {
  id: string
  name: string
}

export interface Tenant {
  id: string
  name: string
  description: string | null
  createdAt: string | null
  memberCount: number
}

export async function listTenants(): Promise<TenantSummary[]> {
  const res = await fetchWithAuth(API, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function listTenantsManage(): Promise<Tenant[]> {
  const res = await fetchWithAuth(`${API}/manage`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function createTenant(request: { name: string; description?: string }): Promise<Tenant> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function updateTenant(id: string, request: { name: string; description?: string }): Promise<Tenant> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function deleteTenant(id: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' }, OPTS)
  return handleJsonResponse(res)
}
