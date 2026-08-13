import fetchWithAuth, { handleJsonResponse } from './fetchWithAuth'

const API = '/api/tenants'

// the admin page surfaces backend messages itself (e.g. delete-blocked-by-members),
// so the global forbidden toast is suppressed to avoid double notifications
const OPTS = { forbiddenEvent: false }

/** Minimal view for selectors - available to any authenticated user. */
export interface TenantSummary {
  id: string
  name: string
  /** Badge colour as #RRGGBB; always resolved by the backend, never null. */
  color: string
}

export interface Tenant {
  id: string
  name: string
  description: string | null
  /** null/undefined = all allowed repositories enabled */
  enabledRepositories?: string[] | null
  /** null/undefined = all configured database connections enabled */
  enabledDatabases?: string[] | null
  color: string
  createdAt: string | null
  memberCount: number
}

export interface TenantRequest {
  name: string
  description?: string
  enabledRepositories?: string[] | null
  enabledDatabases?: string[] | null
  /** omitted = keep the current colour on edit, pick a random one on create */
  color?: string
}

/** Global option lists for the tenant entitlement editor (global admins only). */
export interface EntitlementOptions {
  repositories: string[]
  databases: string[]
}

export async function listTenants(): Promise<TenantSummary[]> {
  const res = await fetchWithAuth(API, undefined, OPTS)
  return handleJsonResponse(res)
}

/** The swatches offered by the tenant form; the source of truth lives in TenantPalette. */
export async function getTenantPalette(): Promise<string[]> {
  const res = await fetchWithAuth(`${API}/palette`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function listTenantsManage(): Promise<Tenant[]> {
  const res = await fetchWithAuth(`${API}/manage`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function getEntitlementOptions(): Promise<EntitlementOptions> {
  const res = await fetchWithAuth(`${API}/entitlement-options`, undefined, OPTS)
  return handleJsonResponse(res)
}

export async function createTenant(request: TenantRequest): Promise<Tenant> {
  const res = await fetchWithAuth(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, OPTS)
  return handleJsonResponse(res)
}

export async function updateTenant(id: string, request: TenantRequest): Promise<Tenant> {
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
