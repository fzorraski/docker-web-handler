import { useState, useEffect, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TablePagination, TextField, MenuItem, IconButton, Tooltip, Chip,
  Typography, CircularProgress, ToggleButton, ToggleButtonGroup, Button,
} from '@mui/material'
import { Refresh, Download } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNotification } from '../../NotificationProvider'
import { useTableHeaderTheme } from '../../../hooks/useTableHeaderTheme'
import { useDebouncedValue } from '../../../hooks/useDebouncedValue'
import { toCsv, downloadCsv } from '../../../utils/csv'
import {
  activityByUser, activityByDay, listActivityActions, type ActivityRow,
} from '../../../services/activityService'

/** Same severity colouring as the audit trail, so actions read alike. */
function actionColor(action: string): 'error' | 'warning' | 'success' | 'default' {
  if (/FAILED|DENIED|ERROR/.test(action)) return 'error'
  if (/DELETE|REMOVE|RESET|CLEANUP/.test(action)) return 'warning'
  if (/CREATE|LOGIN$|RESTORE|UPLOAD/.test(action)) return 'success'
  return 'default'
}

type Grouping = 'user' | 'day'

/**
 * The raw counts behind the dashboard. Reads the same window as the charts, so
 * a number the overview shows can be traced to the rows that produced it.
 */
export default function ActivityDetailsView({ from, to }: { from?: string; to?: string }) {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const { theadBg, theadColor } = useTableHeaderTheme()

  const [rows, setRows] = useState<ActivityRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(50)
  const [loading, setLoading] = useState(true)

  const [grouping, setGrouping] = useState<Grouping>('user')
  const [actions, setActions] = useState<string[]>([])
  const [actionFilter, setActionFilter] = useState('')
  const [actorFilter, setActorFilter] = useState('')
  const debouncedActor = useDebouncedValue(actorFilter, 400)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const fetcher = grouping === 'user' ? activityByUser : activityByDay
      const result = await fetcher(page, size, {
        from,
        to,
        actor: debouncedActor || undefined,
        action: actionFilter || undefined,
      })
      setRows(result.rows)
      setTotal(result.total)
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setLoading(false)
    }
  }, [page, size, grouping, actionFilter, debouncedActor, from, to, notify, t])

  useEffect(() => { load() }, [load])
  useEffect(() => { listActivityActions().then(setActions).catch(() => setActions([])) }, [])
  // filters restart from the first page
  useEffect(() => { setPage(0) }, [grouping, actionFilter, debouncedActor, from, to])

  function exportCsv() {
    const headers = grouping === 'day'
      ? [t('activity.day'), t('activity.actor'), t('activity.action'), t('activity.count')]
      : [t('activity.actor'), t('activity.action'), t('activity.count')]
    const body = rows.map(row => grouping === 'day'
      ? [row.day ?? '', row.actor, row.action, row.count]
      : [row.actor, row.action, row.count])
    downloadCsv(`activity-${grouping}-${from ?? 'start'}-${to ?? 'end'}.csv`, toCsv(headers, body))
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 2, alignItems: 'center' }}>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={grouping}
          onChange={(_, value: Grouping | null) => value && setGrouping(value)}
        >
          <ToggleButton value="user">{t('activity.byUser')}</ToggleButton>
          <ToggleButton value="day">{t('activity.byDay')}</ToggleButton>
        </ToggleButtonGroup>
        <TextField
          size="small"
          label={t('activity.actor')}
          value={actorFilter}
          onChange={(e) => setActorFilter(e.target.value)}
          sx={{ minWidth: 150 }}
        />
        <TextField
          size="small"
          select
          label={t('activity.action')}
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          sx={{ minWidth: 190 }}
        >
          <MenuItem value="">{t('activity.allActions')}</MenuItem>
          {actions.map((action) => (
            <MenuItem key={action} value={action}>{action}</MenuItem>
          ))}
        </TextField>
        <Tooltip title={t('activity.refresh')}>
          <IconButton onClick={load} size="small"><Refresh /></IconButton>
        </Tooltip>
        <Button
          size="small"
          startIcon={<Download />}
          onClick={exportCsv}
          disabled={rows.length === 0}
        >
          {t('activity.export')}
        </Button>
        {loading && <CircularProgress size={20} />}
      </Box>

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow sx={{ '& th': { bgcolor: theadBg, color: theadColor, fontWeight: 600 } }}>
              {grouping === 'day' && <TableCell>{t('activity.day')}</TableCell>}
              <TableCell>{t('activity.actor')}</TableCell>
              <TableCell>{t('activity.action')}</TableCell>
              <TableCell align="right">{t('activity.count')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 && !loading && (
              <TableRow>
                <TableCell colSpan={grouping === 'day' ? 4 : 3} align="center"
                  sx={{ py: 4, color: 'text.secondary' }}>
                  {t('activity.empty')}
                </TableCell>
              </TableRow>
            )}
            {rows.map((row, index) => (
              <TableRow key={`${row.day ?? 'total'}-${row.actor}-${row.action}-${index}`} hover>
                {grouping === 'day' && (
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{row.day}</TableCell>
                )}
                <TableCell sx={{ fontWeight: 500 }}>{row.actor}</TableCell>
                <TableCell>
                  <Chip label={row.action} size="small" color={actionColor(row.action)} variant="outlined" />
                </TableCell>
                <TableCell align="right">
                  <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                    {row.count}
                  </Typography>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={total}
          page={page}
          onPageChange={(_, newPage) => setPage(newPage)}
          rowsPerPage={size}
          onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value, 10)); setPage(0) }}
          rowsPerPageOptions={[25, 50, 100]}
        />
      </TableContainer>
    </Box>
  )
}
