import fetchWithAuth from './fetchWithAuth'

const API = '/api/settings'

export interface RuntimeSetting {
  key: string
  type: 'boolean' | 'integer'
  value: boolean | number
  defaultValue: boolean | number
  overridden: boolean
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}

export async function listSettings(): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(API)
  return handleResponse(res)
}

export async function updateSettings(changes: Record<string, boolean | number>): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(API, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(changes),
  })
  return handleResponse(res)
}

export async function resetSetting(key: string): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(key)}`, { method: 'DELETE' })
  return handleResponse(res)
}
