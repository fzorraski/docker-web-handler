import { useMemo } from 'react'
import {
  Box, Typography, Table, TableBody, TableCell, TableHead, TableRow, Chip,
} from '@mui/material'
import {
  AreaChart, Area, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid,
  Tooltip as RechartsTooltip, Legend, ResponsiveContainer,
} from 'recharts'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { ChartCard, KpiTile, TileRow, useChartTheme } from './ActivityCards'
import { ACTIVITY_CATEGORIES, categoryColor } from '../../../utils/activityCategory'
import type { ActivityOverview } from '../../../services/activityService'

export default function ActivityOverviewView({ overview, days }: {
  overview: ActivityOverview
  days: number
}) {
  const { t } = useTranslation()
  const chart = useChartTheme()
  const { totals, previous } = overview

  const label = (category: string) =>
    t(`activity.category.${category}` as 'activity.category.OTHER')

  // recharts wants one flat object per point, with a key per series
  const trend = useMemo(
    () => overview.daily.map(point => ({ day: point.day, ...point.byCategory })),
    [overview.daily],
  )

  // only the categories that actually occur, in the fixed slot order - an
  // empty series would still claim a legend entry and a colour
  const series = useMemo(() => {
    const present = new Set(overview.categories.map(c => c.category))
    return ACTIVITY_CATEGORIES.filter(category => present.has(category))
  }, [overview.categories])

  const totalEvents = overview.categories.reduce((sum, c) => sum + c.count, 0)

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <TileRow>
        <KpiTile
          label={t('activity.kpi.events')}
          hint={t('activity.kpi.eventsHint')}
          value={totals.events}
          current={totals.events}
          previous={previous.events}
          days={days}
          polarity="neutral"
        />
        <KpiTile
          label={t('activity.kpi.operational')}
          hint={t('activity.kpi.operationalHint')}
          value={totals.operational}
          current={totals.operational}
          previous={previous.operational}
          days={days}
          polarity="moreIsBetter"
        />
        <KpiTile
          label={t('activity.kpi.activeUsers')}
          hint={t('activity.kpi.activeUsersHint')}
          value={totals.activeUsers}
          current={totals.activeUsers}
          previous={previous.activeUsers}
          days={days}
          polarity="moreIsBetter"
        />
        <KpiTile
          label={t('activity.kpi.failures')}
          hint={t('activity.kpi.failuresHint')}
          value={totals.failures}
          current={totals.failures}
          previous={previous.failures}
          days={days}
          polarity="lessIsBetter"
        />
      </TileRow>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' } }}>
        <KpiTile
          label={t('activity.kpi.busiestDay')}
          value={totals.busiestDay ? dayjs(totals.busiestDay).format('DD/MM/YYYY') : '-'}
          caption={totals.busiestDay
            ? `${totals.busiestDayCount.toLocaleString()} ${t('activity.kpi.events').toLowerCase()}`
            : undefined}
        />
        <KpiTile
          label={t('activity.kpi.busiestUser')}
          value={totals.busiestUser ?? '-'}
          caption={totals.busiestUser
            ? `${totals.busiestUserCount.toLocaleString()} ${t('activity.kpi.events').toLowerCase()}`
            : undefined}
        />
      </Box>

      <ChartCard title={t('activity.trend.title')} subtitle={t('activity.trend.subtitle')}>
        <ResponsiveContainer width="100%" height={300}>
          <AreaChart data={trend} margin={{ top: 5, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={chart.grid} vertical={false} />
            <XAxis
              dataKey="day"
              tick={chart.tick}
              minTickGap={28}
              tickFormatter={(day: string) => dayjs(day).format('DD/MM')}
            />
            <YAxis tick={chart.tick} allowDecimals={false} width={44} />
            <RechartsTooltip
              {...chart.tooltip}
              labelFormatter={(day) => dayjs(String(day)).format('DD/MM/YYYY')}
              formatter={(value, name) => [Number(value).toLocaleString(), label(String(name))]}
            />
            <Legend formatter={(name) => label(String(name))} />
            {series.map(category => (
              <Area
                key={category}
                type="monotone"
                dataKey={category}
                stackId="events"
                stroke={categoryColor(category, chart.mode)}
                strokeWidth={2}
                fill={categoryColor(category, chart.mode)}
                fillOpacity={0.35}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </ChartCard>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
        <ChartCard title={t('activity.mix.title')} subtitle={t('activity.mix.subtitle')}>
          {overview.categories.length === 0 ? (
            <Empty text={t('activity.empty')} />
          ) : (
            <>
              <ResponsiveContainer width="100%" height={260}>
                <PieChart>
                  <Pie
                    data={overview.categories}
                    dataKey="count"
                    nameKey="category"
                    innerRadius={62}
                    outerRadius={96}
                    paddingAngle={2}
                    stroke={chart.surface}
                    strokeWidth={2}
                  >
                    {overview.categories.map(slice => (
                      <Cell key={slice.category} fill={categoryColor(slice.category, chart.mode)} />
                    ))}
                  </Pie>
                  <RechartsTooltip
                    {...chart.tooltip}
                    formatter={(value, name) => [Number(value).toLocaleString(), label(String(name))]}
                  />
                  <Legend formatter={(name) => label(String(name))} />
                </PieChart>
              </ResponsiveContainer>
              {/* the slices repeat as text: three of the light-mode hues sit
                  below the contrast floor, and a share is easier to read than
                  to estimate from an arc */}
              <Table size="small">
                <TableBody>
                  {overview.categories.map(slice => (
                    <TableRow key={slice.category}>
                      <TableCell sx={{ borderBottom: 'none', py: 0.4 }}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          <Box sx={{
                            width: 10, height: 10, borderRadius: '2px',
                            bgcolor: categoryColor(slice.category, chart.mode),
                          }} />
                          {label(slice.category)}
                        </Box>
                      </TableCell>
                      <TableCell align="right" sx={{ borderBottom: 'none', py: 0.4, fontFamily: "'JetBrains Mono', monospace" }}>
                        {slice.count.toLocaleString()}
                      </TableCell>
                      <TableCell align="right" sx={{ borderBottom: 'none', py: 0.4, color: 'text.secondary', width: 60 }}>
                        {totalEvents > 0 ? `${Math.round((slice.count / totalEvents) * 100)}%` : '-'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </ChartCard>

        <ChartCard title={t('activity.signIns.title')} subtitle={t('activity.signIns.subtitle')}>
          {overview.signInAttempts.length === 0 ? (
            <Empty text={t('activity.signIns.empty')} />
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('activity.actor')}</TableCell>
                  <TableCell align="right">{t('activity.signIns.failures')}</TableCell>
                  <TableCell align="right">{t('activity.signIns.successes')}</TableCell>
                  <TableCell align="right">{t('activity.security.lastSeen')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {overview.signInAttempts.map(attempt => (
                  <TableRow key={attempt.actor} hover>
                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        {attempt.actor}
                        {!attempt.known && (
                          <Chip label={t('activity.signIns.unknownUser')} size="small" color="error" variant="outlined"
                                sx={{ height: 18, fontSize: '0.65rem' }} />
                        )}
                      </Box>
                    </TableCell>
                    <TableCell align="right">
                      <Chip
                        label={attempt.failures.toLocaleString()}
                        size="small"
                        color={attempt.failures >= 5 || !attempt.known ? 'error' : 'default'}
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary' }}>
                      {attempt.successes.toLocaleString()}
                    </TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>
                      {attempt.lastAt ? dayjs(attempt.lastAt).format('DD/MM/YYYY') : '-'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </ChartCard>

        <ChartCard title={t('activity.security.title')} subtitle={t('activity.security.subtitle')}>
          {overview.failures.length === 0 ? (
            <Empty text={t('activity.security.empty')} />
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('activity.actor')}</TableCell>
                  <TableCell align="right">{t('activity.security.attempts')}</TableCell>
                  <TableCell align="right">{t('activity.security.lastSeen')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {overview.failures.map(failure => (
                  <TableRow key={failure.actor} hover>
                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      {failure.actor}
                    </TableCell>
                    <TableCell align="right">
                      <Chip
                        label={failure.count.toLocaleString()}
                        size="small"
                        color={failure.count >= 5 ? 'error' : 'default'}
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>
                      {failure.lastAt ? dayjs(failure.lastAt).format('DD/MM/YYYY') : '-'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </ChartCard>
      </Box>
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
