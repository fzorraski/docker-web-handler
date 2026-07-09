import { useState, useEffect } from 'react'
import { useAuth } from '../components/AuthProvider'
import { listTenants } from '../services/tenantService'

/**
 * Resolves tenant ids to display names for list columns. Empty when RBAC is
 * off (no tenant columns are rendered then).
 */
export function useTenantNames(): Map<string, string> {
  const { rbacEnabled, authenticated } = useAuth()
  const [names, setNames] = useState<Map<string, string>>(new Map())

  useEffect(() => {
    if (!rbacEnabled || !authenticated) return
    listTenants()
      .then(tenants => setNames(new Map(tenants.map(tn => [tn.id, tn.name]))))
      .catch(() => setNames(new Map()))
  }, [rbacEnabled, authenticated])

  return names
}
