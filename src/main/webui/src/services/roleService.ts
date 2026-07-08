import fetchWithAuth from './fetchWithAuth'

const API = '/api/roles'

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

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}

export async function listRoles(): Promise<AppRole[]> {
  const res = await fetchWithAuth(API)
  return handleResponse(res)
}

export async function getPermissionCatalog(): Promise<PermissionInfo[]> {
  const res = await fetchWithAuth(`${API}/permissions`)
  return handleResponse(res)
}

export async function createRole(request: { name: string; description?: string; permissions: string[] }): Promise<AppRole> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  return handleResponse(res)
}

export async function updateRole(id: string, request: { name: string; description?: string; permissions: string[] }): Promise<AppRole> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  return handleResponse(res)
}

export async function deleteRole(id: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(id)}`, { method: 'DELETE' })
  return handleResponse(res)
}
