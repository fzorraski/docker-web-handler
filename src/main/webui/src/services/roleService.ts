import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/roles'

// the admin page surfaces backend messages itself (e.g. the super-admin rails),
// so the global forbidden toast is suppressed to avoid double notifications
const OPTS = { forbiddenEvent: false }

export interface AppRole {
  id: string
  name: string
  description: string | null
  permissions: string[]
  builtIn: boolean
  createdAt?: string | null
}

export interface PermissionInfo {
  name: string
  category: string
}

export async function listRoles(): Promise<AppRole[]> {
  const res = await fetchWithAuth(API, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function getPermissionCatalog(): Promise<PermissionInfo[]> {
  const res = await fetchWithAuth(`${API}/permissions`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function createRole(request: { name: string; description?: string; permissions: string[] }): Promise<AppRole> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function updateRole(id: string, request: { name: string; description?: string; permissions: string[] }): Promise<AppRole> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function deleteRole(id: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' }, OPTS)
  return handleJsonResponse(res)
}
