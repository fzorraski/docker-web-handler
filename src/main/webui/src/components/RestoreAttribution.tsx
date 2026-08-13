import { Box, Tooltip, Typography } from '@mui/material'
import { PersonOutline } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import TenantChip from './TenantChip'
import type { TenantLookup } from '../hooks/useTenants'
import type { ActiveRestore } from '../services/dumpService'

interface RestoreAttributionProps {
  restore: ActiveRestore
  tenants: TenantLookup
}

/**
 * Who is running a restore, next to the "restore in progress" banner line:
 * the username that triggered it ("system" for a scheduler-driven run) and the
 * owning tenant, resolved to its display name.
 *
 * Both are optional on the wire - RBAC may be off, and a restore started by an
 * untenanted user belongs to no tenant - so each part renders only when the
 * backend actually knows it, and the whole row disappears when neither is set.
 */
export default function RestoreAttribution({ restore, tenants }: RestoreAttributionProps) {
  const { t } = useTranslation()
  const startedBy = restore.startedBy
  const tenantId = restore.tenantId

  if (!startedBy && !tenantId) {
    return null
  }

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 0.25, flexWrap: 'wrap' }}>
      {startedBy && (
        <Typography
          variant="caption"
          sx={{ color: 'text.secondary', display: 'flex', alignItems: 'center', gap: 0.5 }}
        >
          <PersonOutline sx={{ fontSize: '0.9rem' }} />
          {t('common.startedBy', { user: startedBy })}
        </Typography>
      )}
      {tenantId && (
        <Tooltip title={t('tenants.tenant')} arrow>
          <TenantChip tenant={tenants.get(tenantId)} fallbackLabel={tenantId} />
        </Tooltip>
      )}
    </Box>
  )
}
