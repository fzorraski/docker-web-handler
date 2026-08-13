import { useMemo } from 'react'
import { Box, Tooltip, Typography, useTheme } from '@mui/material'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { heatColor, type ThemeMode } from '../../../utils/activityCategory'
import type { ActivityHeatCell } from '../../../services/activityService'

/**
 * User-by-day grid. Built from CSS grid rather than a charting library: the
 * marks are plain rectangles, and a real DOM node per cell is what makes each
 * one hoverable and reachable by a screen reader.
 *
 * @param cells   sparse - only days with activity arrive from the API
 * @param actors  row order, already ranked by the caller
 */
export default function ActivityHeatmap({ cells, actors, from, to }: {
  cells: ActivityHeatCell[]
  actors: string[]
  from: string
  to: string
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const mode = theme.palette.mode as ThemeMode

  const days = useMemo(() => {
    const list: string[] = []
    const end = dayjs(to)
    for (let day = dayjs(from); !day.isAfter(end); day = day.add(1, 'day')) {
      list.push(day.format('YYYY-MM-DD'))
    }
    return list
  }, [from, to])

  const { counts, max } = useMemo(() => {
    const map = new Map<string, number>()
    let highest = 0
    for (const cell of cells) {
      map.set(`${cell.actor}|${cell.day}`, cell.count)
      highest = Math.max(highest, cell.count)
    }
    return { counts: map, max: highest }
  }, [cells])

  if (actors.length === 0 || days.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
        {t('activity.heatmap.empty')}
      </Typography>
    )
  }

  // one column per day, sized to fit but never so narrow it disappears; the
  // grid scrolls inside the card instead of stretching the page
  const cellSize = days.length > 45 ? 12 : days.length > 25 ? 16 : 22

  return (
    <Box>
      <Box sx={{ overflowX: 'auto', pb: 1 }}>
        <Box sx={{ display: 'grid', gridTemplateColumns: `auto repeat(${days.length}, ${cellSize}px)`, gap: '2px', alignItems: 'center' }}>
          {/* header row: only every few days get a label, or they collide */}
          <Box />
          {days.map((day, index) => (
            <Typography
              key={day}
              variant="caption"
              sx={{
                fontSize: 9, color: 'text.secondary', textAlign: 'center',
                whiteSpace: 'nowrap', overflow: 'visible',
              }}
            >
              {index % Math.ceil(days.length / 10) === 0 ? dayjs(day).format('DD/MM') : ''}
            </Typography>
          ))}

          {actors.map(actor => (
            <Box key={actor} sx={{ display: 'contents' }}>
              <Typography
                variant="caption"
                sx={{
                  pr: 1, textAlign: 'right', whiteSpace: 'nowrap', maxWidth: 160,
                  overflow: 'hidden', textOverflow: 'ellipsis',
                  fontFamily: "'JetBrains Mono', monospace", fontSize: '0.72rem',
                }}
                title={actor}
              >
                {actor}
              </Typography>
              {days.map(day => {
                const count = counts.get(`${actor}|${day}`) ?? 0
                return (
                  <Tooltip
                    key={day}
                    title={`${actor} - ${dayjs(day).format('DD/MM/YYYY')}: ${count.toLocaleString()}`}
                  >
                    <Box
                      sx={{
                        height: cellSize,
                        borderRadius: '2px',
                        bgcolor: count > 0 ? heatColor(count, max, mode) : 'action.hover',
                        cursor: 'default',
                      }}
                    />
                  </Tooltip>
                )
              })}
            </Box>
          ))}
        </Box>
      </Box>

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5, mt: 1 }}>
        <Typography variant="caption" color="text.secondary">{t('activity.heatmap.less')}</Typography>
        {[0.05, 0.25, 0.5, 0.75, 1].map(step => (
          <Box
            key={step}
            sx={{
              width: 12, height: 12, borderRadius: '2px',
              bgcolor: heatColor(Math.max(1, Math.round(step * max)), max || 1, mode),
            }}
          />
        ))}
        <Typography variant="caption" color="text.secondary">{t('activity.heatmap.more')}</Typography>
      </Box>
    </Box>
  )
}
