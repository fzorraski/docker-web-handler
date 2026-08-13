import { Box, Chip, Tooltip } from '@mui/material'
import { Share } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import TenantChip from './TenantChip'
import { tenantName, type TenantLookup } from '../hooks/useTenants'

interface Props {
  /** owning tenant id; null/absent renders as "no tenant" */
  tenantId?: string | null
  sharedWithTenants?: string[]
  /** id -> summary, from useTenants() */
  tenants: TenantLookup
}

/**
 * The tenant column for any shareable resource: the owner as a chip, plus a
 * count of the tenants it was shared with. Used by dumps, snapshots,
 * containers and schedules, which all read the same two fields.
 */
export default function TenantCell({ tenantId, sharedWithTenants, tenants }: Props) {
  const { t } = useTranslation()
  const name = (id: string) => tenantName(tenants, id)
  const shared = sharedWithTenants ?? []

  return (
    <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
      {tenantId
        ? <TenantChip tenant={tenants.get(tenantId)} fallbackLabel={tenantId} />
        : '-'}
      {shared.length > 0 && (
        <Tooltip title={`${t('tenants.sharedWith')}: ${shared.map(name).join(', ')}`}>
          <Chip icon={<Share sx={{ fontSize: 14 }} />} label={shared.length} size="small" variant="outlined" color="info" />
        </Tooltip>
      )}
    </Box>
  )
}
