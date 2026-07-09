import { useState, useEffect } from 'react'
import { TextField, MenuItem } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAuth } from './AuthProvider'
import { P } from '../utils/permissions'
import { listTenants, type TenantSummary } from '../services/tenantService'

interface Props {
  /** '' = no tenant (backend then defaults to the user's own tenant, or none) */
  value: string
  onChange: (tenantId: string) => void
  disabled?: boolean
}

/** Whether the tenant selector applies to the current user (for layout decisions). */
export function useTenantChoice(): boolean {
  const { rbacEnabled, currentUser, hasPermission } = useAuth()
  return rbacEnabled && (hasPermission(P.TENANTS_VIEW_ALL) || (currentUser?.tenants.length ?? 0) > 1)
}

/**
 * Owning-tenant selector for creation flows. Renders only when there is an
 * actual choice to make: users bound to zero or one tenant get the right
 * default from the backend, so no field is shown.
 */
export default function TenantSelect({ value, onChange, disabled }: Props) {
  const { t } = useTranslation()
  const { rbacEnabled, currentUser, hasPermission } = useAuth()
  const canViewAll = rbacEnabled && hasPermission(P.TENANTS_VIEW_ALL)
  const ownTenants = currentUser?.tenants ?? []

  const [allTenants, setAllTenants] = useState<TenantSummary[]>([])

  const visible = rbacEnabled && (canViewAll || ownTenants.length > 1)

  useEffect(() => {
    if (visible && canViewAll) {
      listTenants().then(setAllTenants).catch(() => setAllTenants([]))
    }
  }, [visible, canViewAll])

  // multi-tenant members must pick one of their squads; default to the first
  useEffect(() => {
    if (visible && !canViewAll && value === '' && ownTenants.length > 0) {
      onChange(ownTenants[0].id)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, canViewAll, value, ownTenants.length])

  if (!visible) return null

  const options = canViewAll ? allTenants : ownTenants
  // members must always own the new resource - render the default before the
  // sync effect fires, so MUI never sees a value without a matching option
  const effectiveValue = !canViewAll && value === '' && ownTenants.length > 0
    ? ownTenants[0].id
    : value

  return (
    <TextField
      select
      label={t('tenants.tenant')}
      value={effectiveValue}
      onChange={(e) => onChange(e.target.value)}
      size="small"
      fullWidth
      disabled={disabled}
      helperText={t('tenants.selectorHint')}
    >
      {canViewAll && <MenuItem value="">{t('tenants.noneOption')}</MenuItem>}
      {options.map((tn) => (
        <MenuItem key={tn.id} value={tn.id}>{tn.name}</MenuItem>
      ))}
    </TextField>
  )
}
