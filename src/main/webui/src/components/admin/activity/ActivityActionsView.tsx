import { useMemo } from 'react'
import {
  Box, Typography, Table, TableBody, TableCell, TableHead, TableRow, Chip,
} from '@mui/material'
import {
  BarChart, Bar, Cell, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer,
} from 'recharts'
import { useTranslation } from 'react-i18next'
import TenantChip from '../../TenantChip'
import { ChartCard, useChartTheme } from './ActivityCards'
import { categoryColor } from '../../../utils/activityCategory'
import { isSystemEntry, resolveTenantLabel } from '../../../utils/auditTenant'
import type { TenantLookup } from '../../../hooks/useTenants'
import type { ActivityOverview } from '../../../services/activityService'

export default function ActivityActionsView({ overview, tenants }: {
  overview: ActivityOverview
  tenants: TenantLookup
}) {
  const { t } = useTranslation()
  const chart = useChartTheme()

  const label = (category: string) =>
    t(`activity.category.${category}` as 'activity.category.OTHER')

  // horizontal bars, longest at the top: action names are long, and a vertical
  // chart would either clip them or turn them on their side
  const bars = useMemo(() => [...overview.topActions].reverse(), [overview.topActions])
  const totalEvents = overview.topActions.reduce((sum, action) => sum + action.count, 0)

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <ChartCard title={t('activity.actionsView.title')} subtitle={t('activity.actionsView.subtitle')}>
        {overview.topActions.length === 0 ? (
          <Empty text={t('activity.actionsView.empty')} />
        ) : (
          <>
            <ResponsiveContainer width="100%" height={Math.max(200, bars.length * 30 + 40)}>
              <BarChart data={bars} layout="vertical" margin={{ top: 5, right: 24, left: 10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={chart.grid} horizontal={false} />
                <XAxis type="number" tick={chart.tick} allowDecimals={false} />
                <YAxis type="category" dataKey="action" tick={{ ...chart.tick, fontSize: 10 }} width={165} />
                <RechartsTooltip
                  {...chart.tooltip}
                  formatter={(value, _name, entry) => [
                    Number(value).toLocaleString(),
                    label((entry as { payload?: { category?: string } })?.payload?.category ?? 'OTHER'),
                  ]}
                />
                {/* one series, coloured by the action's category - the legend
                    lives in the table below, where the names are spelled out */}
                <Bar dataKey="count" radius={[0, 4, 4, 0]}>
                  {bars.map(action => (
                    <Cell key={action.action} fill={categoryColor(action.category, chart.mode)} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>

            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('activity.action')}</TableCell>
                  <TableCell>{t('activity.mix.title')}</TableCell>
                  <TableCell align="right">{t('activity.count')}</TableCell>
                  <TableCell align="right">{t('activity.actionsView.users')}</TableCell>
                  <TableCell align="right">{t('activity.actionsView.share')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {overview.topActions.map(action => (
                  <TableRow key={action.action} hover>
                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.78rem' }}>
                      {action.action}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <Box sx={{
                          width: 10, height: 10, borderRadius: '2px',
                          bgcolor: categoryColor(action.category, chart.mode),
                        }} />
                        <Typography variant="body2">{label(action.category)}</Typography>
                      </Box>
                    </TableCell>
                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {action.count.toLocaleString()}
                    </TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary' }}>{action.users}</TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary' }}>
                      {totalEvents > 0 ? `${Math.round((action.count / totalEvents) * 100)}%` : '-'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </ChartCard>

      <ChartCard title={t('activity.tenantsView.title')}>
        {overview.tenants.length === 0 ? (
          <Empty text={t('activity.tenantsView.empty')} />
        ) : (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('activity.tenant')}</TableCell>
                <TableCell align="right">{t('activity.count')}</TableCell>
                <TableCell align="right">{t('activity.tenantsView.users')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {overview.tenants.map(tenant => (
                <TableRow key={tenant.tenantId ?? 'system'} hover>
                  <TableCell>
                    {tenant.tenantId
                      ? <TenantChip tenant={tenants.get(tenant.tenantId)}
                                    fallbackLabel={resolveTenantLabel(tenant.tenantId, tenants, t('activity.systemTenant'))} />
                      : <Chip label={t('activity.systemTenant')} size="small" variant="outlined" />}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                    {tenant.count.toLocaleString()}
                  </TableCell>
                  <TableCell align="right" sx={{ color: 'text.secondary' }}>{tenant.users}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </ChartCard>
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
