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

// ---- dashboard ----

export interface ActivityTotals {
  events: number
  /** everything except AUTH - the score the ranking is built on */
  operational: number
  auth: number
  failures: number
  activeUsers: number
  distinctActions: number
  busiestDay: string | null
  busiestDayCount: number
  busiestUser: string | null
  busiestUserCount: number
}

/** Counts keyed by category name, every category present even at zero. */
export type CategoryCounts = Record<string, number>

export interface ActivityDayPoint {
  day: string
  total: number
  byCategory: CategoryCounts
}

export interface ActivityCategoryCount {
  category: string
  count: number
}

export interface ActivityActionCount {
  action: string
  category: string
  count: number
  /** distinct people who performed it */
  users: number
}

export interface ActivityUserRank {
  actor: string
  total: number
  operational: number
  auth: number
  failures: number
  /** the same score over the preceding period, for the trend arrow */
  previousOperational: number
  byCategory: CategoryCounts
  topAction: string | null
  activeDays: number
  lastActive: string | null
}

export interface ActivityTenantCount {
  tenantId: string | null
  count: number
  users: number
}

export interface ActivityFailureCount {
  actor: string
  count: number
  lastAt: string | null
}

/** One username that failed to sign in; known = matches a registered account. */
export interface ActivitySignInAttempt {
  actor: string
  known: boolean
  failures: number
  successes: number
  lastAt: string | null
}

export interface ActivityHeatCell {
  actor: string
  day: string
  count: number
}

export interface ActivityOverview {
  from: string
  to: string
  /** last day the roll-up covers; anything after it is read live */
  summarisedThrough: string | null
  totals: ActivityTotals
  previous: ActivityTotals
  daily: ActivityDayPoint[]
  categories: ActivityCategoryCount[]
  topActions: ActivityActionCount[]
  ranking: ActivityUserRank[]
  tenants: ActivityTenantCount[]
  failures: ActivityFailureCount[]
  signInAttempts: ActivitySignInAttempt[]
  heatmap: ActivityHeatCell[]
}

export interface OverviewFilters {
  from?: string
  to?: string
  tenant?: string
  topUsers?: number
}

/** Every aggregate the dashboard draws, for one window. */
export async function activityOverview(filters: OverviewFilters): Promise<ActivityOverview> {
  const params = new URLSearchParams()
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (filters.tenant) params.set('tenant', filters.tenant)
  if (filters.topUsers) params.set('topUsers', String(filters.topUsers))
  const res = await fetchWithAuth(`${API}/overview?${params.toString()}`, undefined, OPTS)
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
