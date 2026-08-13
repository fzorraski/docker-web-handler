import { useMemo } from 'react'
import {
  Box, Typography, Table, TableBody, TableCell, TableHead, TableRow, Chip, Tooltip,
} from '@mui/material'
import { TrendingUp, TrendingDown, TrendingFlat } from '@mui/icons-material'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, Legend, ResponsiveContainer,
} from 'recharts'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { ChartCard, useChartTheme } from './ActivityCards'
import ActivityHeatmap from './ActivityHeatmap'
import { ACTIVITY_CATEGORIES, categoryColor, percentDelta } from '../../../utils/activityCategory'
import type { ActivityOverview, ActivityUserRank } from '../../../services/activityService'

/** Categories that make up the score - AUTH is reported, never scored. */
const SCORED = ACTIVITY_CATEGORIES.filter(category => category !== 'AUTH')

export default function ActivityPeopleView({ overview, days }: {
  overview: ActivityOverview
  days: number
}) {
  const { t } = useTranslation()
  const chart = useChartTheme()

  const label = (category: string) =>
    t(`activity.category.${category}` as 'activity.category.OTHER')

  // the bar is the score, so it stacks only the scored categories; its length
  // has to match the number in the Score column or the chart contradicts it
  const bars = useMemo(
    () => overview.ranking
      .map(rank => {
        const row: Record<string, string | number> = { actor: rank.actor }
        for (const category of SCORED) {
          row[category] = rank.byCategory[category] ?? 0
        }
        return row
      })
      .filter(row => SCORED.some(category => (row[category] as number) > 0))
      // recharts draws the first row at the bottom of a vertical chart
      .reverse(),
    [overview.ranking],
  )

  const series = useMemo(
    () => SCORED.filter(category => overview.ranking.some(rank => (rank.byCategory[category] ?? 0) > 0)),
    [overview.ranking],
  )

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <ChartCard title={t('activity.leaderboard.title')} subtitle={t('activity.leaderboard.subtitle')}>
        {overview.ranking.length === 0 ? (
          <Empty text={t('activity.leaderboard.empty')} />
        ) : (
          <>
            {bars.length > 0 && (
              <ResponsiveContainer width="100%" height={Math.max(180, bars.length * 34 + 60)}>
                <BarChart data={bars} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={chart.grid} horizontal={false} />
                  <XAxis type="number" tick={chart.tick} allowDecimals={false} />
                  <YAxis
                    type="category"
                    dataKey="actor"
                    tick={{ ...chart.tick, fontSize: 11 }}
                    width={130}
                  />
                  <RechartsTooltip
                    {...chart.tooltip}
                    formatter={(value, name) => [Number(value).toLocaleString(), label(String(name))]}
                  />
                  <Legend formatter={(name) => label(String(name))} />
                  {series.map((category, index) => (
                    <Bar
                      key={category}
                      dataKey={category}
                      stackId="score"
                      fill={categoryColor(category, chart.mode)}
                      /* a hairline of surface between segments keeps two
                         adjacent fills from reading as one block */
                      stroke={chart.surface}
                      strokeWidth={1}
                      radius={index === series.length - 1 ? [0, 4, 4, 0] : undefined}
                    />
                  ))}
                </BarChart>
              </ResponsiveContainer>
            )}

            <RankingTable ranking={overview.ranking} days={days} />
          </>
        )}
      </ChartCard>

      <ChartCard title={t('activity.heatmap.title')} subtitle={t('activity.heatmap.subtitle')}>
        <ActivityHeatmap
          cells={overview.heatmap}
          actors={overview.ranking.map(rank => rank.actor)}
          from={overview.from}
          to={overview.to}
        />
      </ChartCard>
    </Box>
  )
}

function RankingTable({ ranking, days }: { ranking: ActivityUserRank[]; days: number }) {
  const { t } = useTranslation()

  return (
    <Box sx={{ overflowX: 'auto', mt: 1 }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 40 }}>{t('activity.leaderboard.rank')}</TableCell>
            <TableCell>{t('activity.actor')}</TableCell>
            <TableCell align="right">{t('activity.leaderboard.score')}</TableCell>
            <TableCell align="right">{t('activity.leaderboard.auth')}</TableCell>
            <TableCell align="right">{t('activity.leaderboard.failures')}</TableCell>
            <TableCell>{t('activity.leaderboard.topAction')}</TableCell>
            <TableCell align="right">{t('activity.leaderboard.activeDays')}</TableCell>
            <TableCell align="right">{t('activity.leaderboard.lastActive')}</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {ranking.map((rank, index) => {
            const delta = percentDelta(rank.operational, rank.previousOperational)
            const Arrow = delta === null || delta === 0 ? TrendingFlat : delta > 0 ? TrendingUp : TrendingDown
            const color = delta === null || delta === 0 ? 'text.disabled'
              : delta > 0 ? 'success.main' : 'error.main'
            return (
              <TableRow key={rank.actor} hover>
                <TableCell sx={{ color: 'text.secondary' }}>{index + 1}</TableCell>
                <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', fontWeight: 600 }}>
                  {rank.actor}
                </TableCell>
                <TableCell align="right">
                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                    <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 600 }}>
                      {rank.operational.toLocaleString()}
                    </Typography>
                    <Tooltip title={delta === null
                      ? t('activity.noBaseline')
                      : `${delta > 0 ? '+' : ''}${Math.round(delta)}% ${t('activity.vsPrevious', { days })}`}>
                      <Arrow sx={{ fontSize: 15, color }} />
                    </Tooltip>
                  </Box>
                </TableCell>
                <TableCell align="right" sx={{ color: 'text.secondary' }}>
                  {rank.auth.toLocaleString()}
                </TableCell>
                <TableCell align="right">
                  {rank.failures > 0
                    ? <Chip label={rank.failures} size="small" color="error" variant="outlined" />
                    : <Typography variant="body2" color="text.disabled">-</Typography>}
                </TableCell>
                <TableCell>
                  {rank.topAction
                    ? <Chip label={rank.topAction} size="small" variant="outlined" />
                    : '-'}
                </TableCell>
                <TableCell align="right" sx={{ color: 'text.secondary' }}>
                  {rank.activeDays}
                </TableCell>
                <TableCell align="right" sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>
                  {rank.lastActive ? dayjs(rank.lastActive).format('DD/MM/YYYY') : '-'}
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </Box>
  )
}

function Empty({ text }: { text: string }) {
  return (
    <Typography variant="body2" color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
      {text}
    </Typography>
  )
}
