import { useState, useEffect } from 'react'
import { useAuth } from '../components/AuthProvider'
import { listTenants, type TenantSummary } from '../services/tenantService'

/** Tenant id -> summary, as every chip and table lookup receives it. */
export type TenantLookup = Map<string, TenantSummary>

/**
 * Tenant id -> summary (name + badge colour), for the chips and the name-based
 * sorting and filtering in the resource tables. Empty when RBAC is off, where
 * no tenant column is rendered at all.
 */
export function useTenants(): TenantLookup {
  const { rbacEnabled, authenticated } = useAuth()
  const [tenants, setTenants] = useState<TenantLookup>(new Map())

  useEffect(() => {
    if (!rbacEnabled || !authenticated) return
    listTenants()
      .then(list => setTenants(new Map(list.map(tn => [tn.id, tn]))))
      .catch(() => setTenants(new Map()))
  }, [rbacEnabled, authenticated])

  return tenants
}

/** The display name for a tenant id, falling back to the raw id. */
export function tenantName(tenants: TenantLookup, id: string): string {
  return tenants.get(id)?.name ?? id
}
