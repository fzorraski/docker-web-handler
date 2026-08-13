import { useState, useEffect, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TablePagination, TextField, MenuItem, IconButton, Tooltip, Chip,
  Typography, CircularProgress, InputAdornment, Divider,
} from '@mui/material'
import {
  Search, Refresh, Close, KeyboardArrowUp, KeyboardArrowDown,
} from '@mui/icons-material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import { type Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'
import { useNotification } from '../NotificationProvider'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useTenants } from '../../hooks/useTenants'
import TenantChip from '../TenantChip'
import { useAuth } from '../AuthProvider'
import { P } from '../../utils/permissions'
import { resolveTenantLabel, isSystemEntry } from '../../utils/auditTenant'
import { formatDate, formatRelative } from '../../utils/format'
import { searchAudit, listAuditActions, type AuditEntry } from '../../services/auditService'

/** Auth0-logs-style severity coloring derived from the action name. */
function actionColor(action: string): 'error' | 'warning' | 'success' | 'default' {
  if (/FAILED|DENIED|ERROR/.test(action)) return 'error'
  // UNPROTECT first — it re-arms deletion, so it reads as a warning, not a success
  if (/DELETE|REMOVE|RESET|CLEANUP|UNPROTECT/.test(action)) return 'warning'
  if (/CREATE|LOGIN$|RESTORE|UPLOAD|PROTECT$/.test(action)) return 'success'
  return 'default'
}

/** Picker value -> ISO instant (empty/invalid -> undefined). */
function toIso(value: Dayjs | null): string | undefined {
  return value && value.isValid() ? value.toISOString() : undefined
}

function DetailRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, py: 1.1, px: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0, fontWeight: 500 }}>
        {label}
      </Typography>
      <Typography
        variant="body2"
        sx={{
          textAlign: 'right', wordBreak: 'break-word',
          fontFamily: mono ? 'JetBrains Mono, monospace' : undefined,
        }}
      >
        {value}
      </Typography>
    </Box>
  )
}

export default function AuditTab() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const { theadBg, theadColor } = useTableHeaderTheme()

  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<number | null>(null)

  const [actions, setActions] = useState<string[]>([])
  const [actionFilter, setActionFilter] = useState('')
  const [actorFilter, setActorFilter] = useState('')
  const [textFilter, setTextFilter] = useState('')
  const [fromFilter, setFromFilter] = useState<Dayjs | null>(null)
  const [toFilter, setToFilter] = useState<Dayjs | null>(null)
  const debouncedText = useDebouncedValue(textFilter, 400)
  const debouncedActor = useDebouncedValue(actorFilter, 400)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await searchAudit(page, size, {
        action: actionFilter || undefined,
        actor: debouncedActor || undefined,
        q: debouncedText || undefined,
        from: toIso(fromFilter),
        to: toIso(toFilter),
      })
      setEntries(result.entries)
      setTotal(result.total)
      setSelected(null)
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setLoading(false)
    }
  }, [page, size, actionFilter, debouncedActor, debouncedText, fromFilter, toFilter, notify, t])

  useEffect(() => { load() }, [load])
  useEffect(() => { listAuditActions().then(setActions).catch(() => setActions([])) }, [])
  // filters restart from the first page
  useEffect(() => { setPage(0) }, [actionFilter, debouncedActor, debouncedText, fromFilter, toFilter])

  const selectedEntry = selected !== null ? entries[selected] : null

  // a scoped reader only ever gets their own tenants back, so the column would
  // be a constant - it is worth showing only to cross-tenant readers
  const { hasPermission, rbacEnabled } = useAuth()
  const tenants = useTenants()
  // hasPermission answers true for everything when RBAC is off, and there is no
  // tenant model at all then - every other tenant column gates the same way
  const showTenant = rbacEnabled && hasPermission(P.TENANTS_VIEW_ALL)
  const tenantLabel = (id?: string | null) =>
    resolveTenantLabel(id, tenants, t('audit.systemTenant'))

  // hand-built, so a new field has to be added here explicitly
  const rawData = (entry: AuditEntry) => JSON.stringify({
    timestamp: entry.timestamp,
    action: entry.action,
    user: entry.actor,
    target: entry.target,
    detail: entry.detail,
    ...(showTenant ? { tenant: tenantLabel(entry.tenantId) } : {}),
  }, null, 2)

  return (
    <Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 2, alignItems: 'center' }}>
        <TextField
          size="small"
          placeholder={t('audit.searchPlaceholder')}
          value={textFilter}
          onChange={(e) => setTextFilter(e.target.value)}
          sx={{ minWidth: 220 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start"><Search fontSize="small" /></InputAdornment>
              ),
            },
          }}
        />
        <TextField
          size="small"
          select
          label={t('audit.action')}
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          sx={{ minWidth: 190 }}
        >
          <MenuItem value="">{t('audit.allActions')}</MenuItem>
          {actions.map((action) => (
            <MenuItem key={action} value={action}>{action}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small"
          label={t('audit.actor')}
          value={actorFilter}
          onChange={(e) => setActorFilter(e.target.value)}
          sx={{ minWidth: 140 }}
        />
        {/* themed pickers (native datetime-local popups ignore the app theme) */}
        <MobileDateTimePicker
          label={t('audit.from')}
          value={fromFilter}
          onChange={setFromFilter}
          slotProps={{
            textField: { size: 'small', sx: { width: 195 } },
            actionBar: { actions: ['clear', 'cancel', 'accept'] },
          }}
        />
        <MobileDateTimePicker
          label={t('audit.to')}
          value={toFilter}
          onChange={setToFilter}
          slotProps={{
            textField: { size: 'small', sx: { width: 195 } },
            actionBar: { actions: ['clear', 'cancel', 'accept'] },
          }}
        />
        <Tooltip title={t('audit.refresh')}>
          <IconButton onClick={load} size="small"><Refresh /></IconButton>
        </Tooltip>
        {loading && <CircularProgress size={20} />}
      </Box>

      {/* table + inline detail panel side by side: the panel lives in the
          content flow (sticky on large screens), so it can never overlap
          the top bar the way a fixed overlay drawer does */}
      <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start', flexDirection: { xs: 'column', lg: 'row' } }}>
      <TableContainer component={Paper} variant="outlined" sx={{ flexGrow: 1, minWidth: 0, width: { xs: '100%', lg: 'auto' } }}>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ '& th': { bgcolor: theadBg, color: theadColor, fontWeight: 600 } }}>
              <TableCell>{t('audit.time')}</TableCell>
              <TableCell>{t('audit.action')}</TableCell>
              <TableCell>{t('audit.actor')}</TableCell>
              <TableCell>{t('audit.target')}</TableCell>
              <TableCell>{t('audit.detail')}</TableCell>
              {showTenant && <TableCell>{t('audit.tenant')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {entries.length === 0 && !loading && (
              <TableRow>
                <TableCell colSpan={showTenant ? 6 : 5} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                  {t('audit.empty')}
                </TableCell>
              </TableRow>
            )}
            {entries.map((entry, index) => (
              <TableRow
                key={`${entry.timestamp}-${index}`}
                hover
                selected={selected === index}
                onClick={() => setSelected(index)}
                sx={{
                  cursor: 'pointer',
                  '&.Mui-selected': {
                    outline: '1px solid',
                    outlineColor: 'primary.main',
                    outlineOffset: -1,
                  },
                }}
              >
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Tooltip title={entry.timestamp ? formatDate(entry.timestamp) : ''}>
                    <span>{formatRelative(entry.timestamp)}</span>
                  </Tooltip>
                </TableCell>
                <TableCell>
                  <Chip label={entry.action} size="small" color={actionColor(entry.action)} variant="outlined" />
                </TableCell>
                <TableCell>{entry.actor ?? '-'}</TableCell>
                <TableCell sx={{ maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {entry.target ?? '-'}
                </TableCell>
                <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'text.secondary' }}>
                  {entry.detail ?? '-'}
                </TableCell>
                {showTenant && (
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {isSystemEntry(entry.tenantId)
                      ? <Typography variant="caption" color="text.secondary">{t('audit.systemTenant')}</Typography>
                      : <TenantChip tenant={entry.tenantId ? tenants.get(entry.tenantId) : undefined} fallbackLabel={tenantLabel(entry.tenantId)} />}
                  </TableCell>
                )}
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
          labelRowsPerPage={t('common.rowsPerPage')}
        />
      </TableContainer>

      {/* Auth0-style detail side panel, inline with the content */}
      {selectedEntry && (
        <Paper
          variant="outlined"
          sx={{
            width: { xs: '100%', lg: 430 },
            flexShrink: 0,
            position: { lg: 'sticky' },
            top: { lg: 16 },
            maxHeight: { lg: 'calc(100vh - 32px)' },
            overflowY: 'auto',
          }}
        >
          <Box sx={{ display: 'flex', flexDirection: 'column', maxHeight: 'inherit' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.5 }}>
              <Typography variant="h6" sx={{ flexGrow: 1, fontSize: '1.05rem' }}>
                {selectedEntry.action}
              </Typography>
              <Tooltip title={t('audit.previous')}>
                <span>
                  <IconButton
                    size="small"
                    disabled={selected === 0}
                    onClick={() => setSelected((s) => (s !== null ? s - 1 : s))}
                  >
                    <KeyboardArrowUp />
                  </IconButton>
                </span>
              </Tooltip>
              <Tooltip title={t('audit.next')}>
                <span>
                  <IconButton
                    size="small"
                    disabled={selected === entries.length - 1}
                    onClick={() => setSelected((s) => (s !== null ? s + 1 : s))}
                  >
                    <KeyboardArrowDown />
                  </IconButton>
                </span>
              </Tooltip>
              <IconButton size="small" onClick={() => setSelected(null)}><Close /></IconButton>
            </Box>
            <Divider />
            <Box sx={{ overflowY: 'auto', flexGrow: 1 }}>
              <Box sx={{ '& > div:not(:last-child)': { borderBottom: 1, borderColor: 'divider' } }}>
                <DetailRow label={t('audit.occurred')}
                           value={formatRelative(selectedEntry.timestamp)} />
                <DetailRow label={t('audit.timestamp')} mono
                           value={selectedEntry.timestamp ?? '-'} />
                <DetailRow label={t('audit.action')} mono value={selectedEntry.action} />
                <DetailRow label={t('audit.actor')} mono value={selectedEntry.actor ?? '-'} />
                <DetailRow label={t('audit.target')} mono value={selectedEntry.target ?? '-'} />
                <DetailRow label={t('audit.detail')} value={selectedEntry.detail ?? '-'} />
                {showTenant && (
                  <DetailRow label={t('audit.tenant')} value={tenantLabel(selectedEntry.tenantId)} />
                )}
              </Box>
              <Box sx={{ px: 2, pb: 3 }}>
                <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>
                  {t('audit.rawData')}
                </Typography>
                <Paper
                  variant="outlined"
                  sx={{
                    p: 1.5, bgcolor: 'background.default',
                    fontFamily: 'JetBrains Mono, monospace', fontSize: '0.78rem',
                    whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowX: 'auto',
                  }}
                >
                  {rawData(selectedEntry)}
                </Paper>
              </Box>
            </Box>
          </Box>
        </Paper>
      )}
      </Box>
    </Box>
  )
}
