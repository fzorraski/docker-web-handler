import fetchWithAuth from './fetchWithAuth'

const API = '/api/users'

export interface AppUser {
  id: string
  username: string
  roleId: string
  roleName: string | null
  enabled: boolean
  createdAt: string | null
  lastLoginAt: string | null
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}

export async function listUsers(): Promise<AppUser[]> {
  const res = await fetchWithAuth(API)
  return handleResponse(res)
}

export async function createUser(request: { username: string; password: string; roleId: string }): Promise<AppUser> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  return handleResponse(res)
}

export async function updateUser(id: string, request: { roleId?: string; enabled?: boolean }): Promise<AppUser> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  return handleResponse(res)
}

export async function resetUserPassword(id: string, password: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}/password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  return handleResponse(res)
}

export async function deleteUser(id: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' })
  return handleResponse(res)
}
