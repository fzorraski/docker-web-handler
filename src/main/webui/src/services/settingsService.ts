import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/settings'

// the settings tab surfaces backend messages itself, so the global forbidden
// toast is suppressed to avoid double notifications
const OPTS = { forbiddenEvent: false }

export interface RuntimeSetting {
  key: string
  type: 'boolean' | 'integer' | 'string'
  value: boolean | number | string
  defaultValue: boolean | number | string
  overridden: boolean
}

export async function listSettings(): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(API, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function updateSettings(changes: Record<string, boolean | number | string>): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(API, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(changes),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function resetSetting(key: string): Promise<RuntimeSetting[]> {
  const res = await fetchWithAuth(`${API}/${encodeURIComponent(key)}`, { method: 'DELETE' }, OPTS)
  return handleJsonResponse(res)
}
