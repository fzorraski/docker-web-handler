import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/reports/activity'

// the admin page surfaces backend messages itself; suppress the global 403 toast
const OPTS = { forbiddenEvent: false }

export interface ActivityRow {
  /** null on range totals, which span several days */
  day: string | null
  actor: string
  action: string
  tenantId: string | null
  count: number
}

export interface ActivityPage {
  rows: ActivityRow[]
  total: number
  page: number
  size: number
}

export interface ActivityFilters {
  /** inclusive ISO dates, e.g. 2026-08-01 */
  from?: string
  to?: string
  actor?: string
  action?: string
}

function query(page: number, size: number, filters: ActivityFilters): string {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (filters.actor) params.set('actor', filters.actor)
  if (filters.action) params.set('action', filters.action)
  return params.toString()
}

/** Totals per user and action over the range. */
export async function activityByUser(
  page: number, size: number, filters: ActivityFilters,
): Promise<ActivityPage> {
  const res = await fetchWithAuth(`${API}/by-user?${query(page, size, filters)}`, undefined, OPTS)
  return handleJsonResponse(res)
}

/** Day-by-day rows, newest first. */
export async function activityByDay(
  page: number, size: number, filters: ActivityFilters,
): Promise<ActivityPage> {
  const res = await fetchWithAuth(`${API}?${query(page, size, filters)}`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function listActivityActions(): Promise<string[]> {
  const res = await fetchWithAuth(`${API}/actions`, undefined, OPTS)
  return handleJsonResponse(res)
}

/** False when the roll-up is not running (legacy file backend, or disabled). */
export async function activitySummaryActive(): Promise<boolean> {
  const res = await fetchWithAuth(`${API}/status`, undefined, OPTS)
  const body: { active?: boolean } = await handleJsonResponse(res)
  return body.active === true
}
