import { Chip, alpha } from '@mui/material'
import type { SxProps, Theme } from '@mui/material'
import type { TenantSummary } from '../services/tenantService'

interface TenantChipProps {
  /** Resolved tenant, when known; a deleted or not-yet-loaded one renders from the id alone. */
  tenant?: TenantSummary
  /** Shown when the tenant is unknown - usually the raw id. */
  fallbackLabel: string
  size?: 'small' | 'medium'
  sx?: SxProps<Theme>
}

/**
 * A tenant badge in that tenant's own colour.
 *
 * <p>Tinted rather than merely outlined: at a glance down a table the fill is
 * what separates one team from another, while the 12% alpha keeps it quiet
 * enough that a row of badges never competes with the data beside it. Both the
 * border and the text take the full colour so the badge survives on either
 * theme, and an unknown tenant falls back to the theme's own outline.</p>
 */
export default function TenantChip({ tenant, fallbackLabel, size = 'small', sx }: TenantChipProps) {
  const color = tenant?.color

  return (
    <Chip
      label={tenant?.name ?? fallbackLabel}
      size={size}
      variant="outlined"
      color={color ? undefined : 'secondary'}
      sx={{
        ...(color
          ? {
              color,
              borderColor: alpha(color, 0.6),
              backgroundColor: alpha(color, 0.12),
              fontWeight: 600,
            }
          : {}),
        ...sx,
      }}
    />
  )
}
