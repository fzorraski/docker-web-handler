import type { ReactNode } from 'react'
import { Box, Paper, Typography, Tooltip, useTheme } from '@mui/material'
import { TrendingUp, TrendingDown, TrendingFlat, HelpOutline } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { percentDelta, type ThemeMode } from '../../../utils/activityCategory'

/**
 * Chart chrome pulled from the MUI theme rather than hard-coded, so grid,
 * ticks and tooltips follow the app's light/dark switch instead of being
 * flipped by a second, parallel definition of "dark".
 */
export function useChartTheme() {
  const theme = useTheme()
  const mode = theme.palette.mode as ThemeMode
  return {
    mode,
    surface: theme.palette.background.paper,
    grid: theme.palette.divider,
    tick: { fontSize: 11, fill: theme.palette.text.secondary },
    tooltip: {
      contentStyle: {
        backgroundColor: theme.palette.background.paper,
        border: `1px solid ${theme.palette.divider}`,
        borderRadius: 8,
        fontSize: 12,
      },
      labelStyle: { color: theme.palette.text.primary, fontWeight: 600 },
      itemStyle: { color: theme.palette.text.secondary },
    },
  }
}

/** A titled panel; every chart on this screen sits in one. */
export function ChartCard({ title, subtitle, action, children }: {
  title: string
  subtitle?: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2, borderRadius: 2, height: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1, mb: 1.5 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="subtitle2" fontWeight={600}>{title}</Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary">{subtitle}</Typography>
          )}
        </Box>
        {action}
      </Box>
      {children}
    </Paper>
  )
}

/**
 * Which direction is the good one. Failed sign-ins going up is not an
 * improvement, so the arrow's colour cannot be derived from its sign alone.
 */
export type Polarity = 'moreIsBetter' | 'lessIsBetter' | 'neutral'

/** A headline number with its change against the preceding period. */
export function KpiTile({ label, value, hint, current, previous, days, polarity = 'neutral', caption }: {
  label: string
  value: string | number
  hint?: string
  /** omit both to show no delta at all (a date or a name has no percentage) */
  current?: number
  previous?: number
  days?: number
  polarity?: Polarity
  caption?: string
}) {
  const { t } = useTranslation()
  const delta = current !== undefined && previous !== undefined
    ? percentDelta(current, previous)
    : undefined

  const rising = delta !== undefined && delta !== null && delta > 0
  const falling = delta !== undefined && delta !== null && delta < 0
  const good = polarity === 'neutral' ? undefined
    : (polarity === 'moreIsBetter' ? rising : falling)
  const color = good === undefined ? 'text.secondary' : good ? 'success.main' : 'error.main'
  const Arrow = rising ? TrendingUp : falling ? TrendingDown : TrendingFlat

  return (
    <Paper variant="outlined" sx={{ p: 2, borderRadius: 2, height: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.5 }}>
        <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: 0.4 }}>
          {label}
        </Typography>
        {hint && (
          <Tooltip title={hint}>
            <HelpOutline sx={{ fontSize: 13, color: 'text.disabled' }} />
          </Tooltip>
        )}
      </Box>
      <Typography variant="h5" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", lineHeight: 1.2 }}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </Typography>
      {caption && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          {caption}
        </Typography>
      )}
      {delta !== undefined && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.75 }}>
          {delta === null ? (
            <Typography variant="caption" color="text.secondary">{t('activity.noBaseline')}</Typography>
          ) : (
            <>
              <Arrow sx={{ fontSize: 15, color }} />
              <Typography variant="caption" sx={{ color, fontWeight: 600 }}>
                {`${delta > 0 ? '+' : ''}${Math.round(delta)}%`}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {t('activity.vsPrevious', { days: days ?? 0 })}
              </Typography>
            </>
          )}
        </Box>
      )}
    </Paper>
  )
}

/** Responsive tile row - one column on a phone, four on a desktop. */
export function TileRow({ children }: { children: ReactNode }) {
  return (
    <Box sx={{
      display: 'grid',
      gap: 2,
      gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' },
    }}>
      {children}
    </Box>
  )
}
