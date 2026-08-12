import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/audit'

// the admin page surfaces backend messages itself; suppress the global 403 toast
const OPTS = { forbiddenEvent: false }

export interface AuditEntry {
  timestamp: string | null
  actor: string | null
  action: string
  target: string | null
  detail: string | null
  /** tenant the action belonged to; null = system / cross-tenant action */
  tenantId: string | null
}

export interface AuditPage {
  entries: AuditEntry[]
  total: number
  page: number
  size: number
}

export interface AuditFilters {
  actor?: string
  action?: string
  q?: string
  from?: string
  to?: string
}

export async function searchAudit(page: number, size: number, filters: AuditFilters): Promise<AuditPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.actor) params.set('actor', filters.actor)
  if (filters.action) params.set('action', filters.action)
  if (filters.q) params.set('q', filters.q)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  const res = await fetchWithAuth(`${API}?${params}`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function listAuditActions(): Promise<string[]> {
  const res = await fetchWithAuth(`${API}/actions`, undefined, OPTS)
  return handleJsonResponse(res)
}
