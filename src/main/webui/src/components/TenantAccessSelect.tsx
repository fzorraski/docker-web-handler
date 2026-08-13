import { useState, useEffect } from 'react'
import { TextField, MenuItem, Checkbox, ListItemText, Chip, Box } from '@mui/material'
import { Star } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from './AuthProvider'
import { useTenantChoice } from './TenantSelect'
import { P } from '../utils/permissions'
import { listTenants, type TenantSummary } from '../services/tenantService'
import {
  ALL_OPTION,
  NONE_OPTION,
  defaultTenantSelection,
  isAllSelected,
  reduceTenantSelection,
  type TenantAccessRules,
} from '../utils/tenantAccess'

interface Props {
  /** Ordered selection: owner first, then shared-with. [] = no tenant. */
  value: string[]
  onChange: (tenantIds: string[]) => void
  disabled?: boolean
}

/** How many chips to show before collapsing the rest into a +N chip. */
const MAX_CHIPS = 3

/**
 * Combined owner + shared-with selector for creation flows that support
 * sharing (dumps, snapshots). The first tenant selected owns the resource;
 * the rest go into its shared-with list.
 *
 * Renders only when there is an actual choice to make, matching the single
 * {@link TenantSelect} it replaces in those flows.
 */
export default function TenantAccessSelect({ value, onChange, disabled }: Props) {
  const { t } = useTranslation()
  const { rbacEnabled, currentUser, hasPermission } = useAuth()
  const canViewAll = rbacEnabled && hasPermission(P.TENANTS_VIEW_ALL)
  const ownTenants = currentUser?.tenants ?? []

  const [allTenants, setAllTenants] = useState<TenantSummary[]>([])

  const visible = useTenantChoice()

  useEffect(() => {
    if (visible && canViewAll) {
      listTenants().then(setAllTenants).catch(() => setAllTenants([]))
    }
  }, [visible, canViewAll])

  const options = canViewAll ? allTenants : ownTenants
  const rules: TenantAccessRules = {
    optionIds: options.map((tn) => tn.id),
    canViewAll,
    ownTenantIds: ownTenants.map((tn) => tn.id),
  }

  // a member must own what they create, and the backend's fallback for "no
  // tenant requested" iterates a Set - so the id has to be sent explicitly or
  // the stamped tenant may differ from the one shown here
  const fallback = defaultTenantSelection(rules)
  useEffect(() => {
    if (visible && !canViewAll && value.length === 0 && fallback.length > 0) {
      onChange(fallback)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, canViewAll, value.length, fallback[0]])

  if (!visible) return null

  // render the default before the sync effect fires, so the field is never
  // momentarily blank for a member
  const effectiveValue = !canViewAll && value.length === 0 ? fallback : value

  const tenantName = (id: string) => options.find((tn) => tn.id === id)?.name ?? id
  const allSelected = isAllSelected(effectiveValue, rules)

  const helperText = effectiveValue.length === 0
    ? t('tenants.noneOptionHint')
    : allSelected
      ? t('tenants.allTenantsHint')
      : t('tenants.accessHint')

  return (
    <TextField
      select
      label={t('tenants.accessLabel')}
      value={effectiveValue}
      onChange={(e) => {
        const next = e.target.value as unknown
        onChange(reduceTenantSelection(effectiveValue, Array.isArray(next) ? next : [String(next)], rules))
      }}
      size="small"
      fullWidth
      disabled={disabled}
      helperText={helperText}
      slotProps={{
        // displayEmpty: MUI skips renderValue for an empty array, which would
        // leave a blank field with an unshrunk label on the "none" state
        select: {
          multiple: true,
          displayEmpty: true,
          renderValue: (selected) => {
            const ids = selected as string[]
            if (ids.length === 0) {
              return <Chip label={t('tenants.noneOption')} size="small" variant="outlined" />
            }
            const shown = ids.slice(0, MAX_CHIPS)
            const hidden = ids.length - shown.length
            return (
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                {shown.map((id, i) => (
                  <Chip
                    key={id}
                    label={tenantName(id)}
                    size="small"
                    icon={i === 0 ? <Star fontSize="small" /> : undefined}
                    color={i === 0 ? 'primary' : 'default'}
                    title={i === 0 ? t('tenants.sharing.owner') : undefined}
                  />
                ))}
                {hidden > 0 && <Chip label={t('tenants.moreTenants', { count: hidden })} size="small" variant="outlined" />}
              </Box>
            )
          },
        },
        inputLabel: { shrink: true },
      }}
    >
      {canViewAll && (
        <MenuItem value={NONE_OPTION}>
          <Checkbox size="small" checked={effectiveValue.length === 0} sx={{ py: 0 }} />
          <ListItemText primary={t('tenants.noneOption')} />
        </MenuItem>
      )}
      {options.length > 1 && (
        <MenuItem value={ALL_OPTION}>
          <Checkbox size="small" checked={allSelected} sx={{ py: 0 }} />
          <ListItemText primary={canViewAll ? t('tenants.allTenantsOption') : t('tenants.allMyTenantsOption')} />
        </MenuItem>
      )}
      {/* no Divider here: Select clones every child with a click handler, and a
          non-MenuItem child would push an undefined value into the selection */}
      {options.map((tn) => (
        <MenuItem key={tn.id} value={tn.id}>
          <Checkbox size="small" checked={effectiveValue.includes(tn.id)} sx={{ py: 0 }} />
          <ListItemText primary={tn.name} />
          {effectiveValue[0] === tn.id && <Star fontSize="small" color="primary" />}
        </MenuItem>
      ))}
    </TextField>
  )
}
