import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/users'

// the admin page surfaces backend messages itself (e.g. the super-admin rails),
// so the global forbidden toast is suppressed to avoid double notifications
const OPTS = { forbiddenEvent: false }

export interface AppUser {
  id: string
  username: string
  roleIds: string[]
  roleNames: string[]
  tenantIds: string[]
  tenantNames: string[]
  enabled: boolean
  createdAt: string | null
  lastLoginAt: string | null
}

export async function listUsers(): Promise<AppUser[]> {
  const res = await fetchWithAuth(API, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function createUser(request: { username: string; password: string; roleIds: string[]; tenantIds?: string[] }): Promise<AppUser> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function updateUser(id: string, request: { roleIds?: string[]; tenantIds?: string[]; enabled?: boolean }): Promise<AppUser> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function resetUserPassword(id: string, password: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}/password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function deleteUser(id: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' }, OPTS)
  return handleJsonResponse(res)
}
