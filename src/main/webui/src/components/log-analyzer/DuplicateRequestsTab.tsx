import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import {
  Box, Typography, Button, LinearProgress, Alert, Paper, Stack, TextField,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TablePagination, TableSortLabel,
  Collapse, IconButton, Chip, Autocomplete, Tooltip, useTheme,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material'
import {
  ContentCopyOutlined, ContentCopy, Refresh, KeyboardArrowDown, KeyboardArrowUp,
  Search, VisibilityOutlined, Close,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { LineLink } from './LineLink'
import { formatDuration } from '../../utils/formatDuration'
import { tryFormatJson } from '../../utils/jsonUtils'
import { copyToClipboard } from '../../utils/clipboard'
import type { DuplicateDetectionResponse, DuplicateGroup } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type State = 'idle' | 'loading' | 'loaded' | 'error'

const MATCH_OPTIONS = [
  { value: 'endpoint+payload', labelKey: 'logAnalyzer.duplicateRequests.matchByEndpointPayload' as const },
  { value: 'endpoint-only', labelKey: 'logAnalyzer.duplicateRequests.matchByEndpointOnly' as const },
]

export function DuplicateRequestsTab({ analysisId, onJumpToLine }: {
  analysisId: string
  onJumpToLine?: (line: number) => void
}) {
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const [state, setState] = useState<State>('idle')
  const [data, setData] = useState<DuplicateDetectionResponse | null>(null)
  const [error, setError] = useState('')

  // Detection params
  const [matchBy, setMatchBy] = useState('endpoint+payload')
  const [timeWindowSeconds, setTimeWindowSeconds] = useState(0)
  const [minOccurrences, setMinOccurrences] = useState(2)

  // Filters
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [filterPayload, setFilterPayload] = useState('')
  const debouncedEndpoint = useDebouncedValue(filterEndpoint, 300)
  const debouncedPayload = useDebouncedValue(filterPayload, 300)

  // Sorting
  type SortField = 'endpoint' | 'count' | 'firstSeen' | 'lastSeen' | 'timeSpan' | 'avgGap'
  const [sort, setSort] = useState<SortField>('count')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')

  // Pagination
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)

  const [expandedGroup, setExpandedGroup] = useState<number | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  // Reset state when analysis changes; abort on unmount
  useEffect(() => {
    setState('idle')
    setData(null)
    setError('')
    setPage(0)
    setExpandedGroup(null)
    return () => { abortRef.current?.abort() }
  }, [analysisId])

  // Unique endpoints from loaded data for Autocomplete
  const endpointOptions = useMemo(() => {
    if (!data) return []
    const set = new Set(data.groups.map(g => g.endpoint))
    return Array.from(set).sort()
  }, [data])

  // Client-side filtering + sorting
  const filteredGroups = useMemo(() => {
    if (!data) return []
    let groups = data.groups
    if (debouncedEndpoint) {
      const lower = debouncedEndpoint.toLowerCase()
      groups = groups.filter(g => g.endpoint.toLowerCase().includes(lower))
    }
    if (debouncedPayload) {
      const lower = debouncedPayload.toLowerCase()
      groups = groups.filter(g => g.payloadPreview.toLowerCase().includes(lower))
    }
    // Sort
    const sorted = [...groups].sort((a, b) => {
      let cmp = 0
      switch (sort) {
        case 'endpoint': cmp = a.endpoint.localeCompare(b.endpoint); break
        case 'count': cmp = a.occurrenceCount - b.occurrenceCount; break
        case 'firstSeen': cmp = (a.firstOccurrence ?? '').localeCompare(b.firstOccurrence ?? ''); break
        case 'lastSeen': cmp = (a.lastOccurrence ?? '').localeCompare(b.lastOccurrence ?? ''); break
        case 'timeSpan': cmp = a.timeSpanMs - b.timeSpanMs; break
        case 'avgGap': cmp = a.avgTimeBetweenMs - b.avgTimeBetweenMs; break
      }
      return sortDir === 'desc' ? -cmp : cmp
    })
    return sorted
  }, [data, debouncedEndpoint, debouncedPayload, sort, sortDir])

  // Paginated slice
  const paginatedGroups = useMemo(() => {
    const start = page * rowsPerPage
    return filteredGroups.slice(start, start + rowsPerPage)
  }, [filteredGroups, page, rowsPerPage])

  const handleSort = (field: SortField) => {
    if (sort === field) {
      setSortDir(prev => prev === 'asc' ? 'desc' : 'asc')
    } else {
      setSort(field)
      setSortDir(field === 'endpoint' ? 'asc' : 'desc')
    }
    setPage(0)
    setExpandedGroup(null)
  }

  const detect = useCallback(async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setState('loading')
    setError('')
    setExpandedGroup(null)
    setPage(0)
    try {
      const result = await logService.getDuplicateRequests(analysisId, {
        matchBy,
        timeWindowSeconds,
        minOccurrences,
      })
      if (controller.signal.aborted) return
      setData(result)
      setState('loaded')
    } catch (err) {
      if (controller.signal.aborted) return
      setError(err instanceof Error ? err.message : t('logAnalyzer.duplicateRequests.error'))
      setState('error')
    }
  }, [analysisId, matchBy, timeWindowSeconds, minOccurrences, t])

  const toggleExpand = (globalIdx: number) => {
    setExpandedGroup(prev => prev === globalIdx ? null : globalIdx)
  }

  const headSx = { bgcolor: theadBg, color: theadColor }

  const controlsRow = (
    <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
      <Autocomplete
        size="small"
        disableClearable
        options={MATCH_OPTIONS}
        getOptionLabel={(o) => t(o.labelKey)}
        value={MATCH_OPTIONS.find(o => o.value === matchBy) ?? MATCH_OPTIONS[0]}
        onChange={(_, v) => v && setMatchBy(v.value)}
        sx={{ minWidth: 220 }}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.duplicateRequests.matchBy')} />}
      />
      <Tooltip title={t('logAnalyzer.duplicateRequests.timeWindowHelp')} arrow placement="top">
        <TextField
          size="small"
          type="number"
          label={t('logAnalyzer.duplicateRequests.timeWindow')}
          value={timeWindowSeconds}
          onChange={(e) => setTimeWindowSeconds(Math.max(0, Number(e.target.value) || 0))}
          slotProps={{ htmlInput: { min: 0, max: 86400 } }}
          sx={{ width: 180 }}
        />
      </Tooltip>
      <TextField
        size="small"
        type="number"
        label={t('logAnalyzer.duplicateRequests.minOccurrences')}
        value={minOccurrences}
        onChange={(e) => setMinOccurrences(Math.max(2, Number(e.target.value) || 2))}
        slotProps={{ htmlInput: { min: 2, max: 100 } }}
        sx={{ width: 150 }}
      />
    </Stack>
  )

  // ---- Idle state ----
  if (state === 'idle') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 2 }}>
        <ContentCopyOutlined sx={{ fontSize: 48, color: 'primary.main', opacity: 0.7 }} />
        <Typography variant="h6" color="text.secondary">{t('logAnalyzer.duplicateRequests.title')}</Typography>
        <Typography variant="body2" color="text.secondary" textAlign="center" maxWidth={500}>
          {t('logAnalyzer.duplicateRequests.description')}
        </Typography>
        {controlsRow}
        <Button variant="outlined" size="large" startIcon={<ContentCopyOutlined />} onClick={detect}>
          {t('logAnalyzer.duplicateRequests.detect')}
        </Button>
      </Box>
    )
  }

  // ---- Loading state ----
  if (state === 'loading') {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <LinearProgress sx={{ mb: 2 }} />
        <Typography color="text.secondary">{t('logAnalyzer.duplicateRequests.detecting')}</Typography>
      </Box>
    )
  }

  // ---- Error state ----
  if (state === 'error') {
    return (
      <Box sx={{ py: 4 }}>
        <Alert severity="error" action={<Button size="small" onClick={detect}>{t('logAnalyzer.duplicateRequests.refresh')}</Button>}>
          {error}
        </Alert>
      </Box>
    )
  }

  // ---- Loaded state ----
  return (
    <Box>
      {/* Controls bar */}
      <Stack direction="row" spacing={2} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
        {controlsRow}
        <Button variant="outlined" size="small" startIcon={<Refresh />} onClick={detect}>
          {t('logAnalyzer.duplicateRequests.refresh')}
        </Button>
      </Stack>

      {/* No duplicates */}
      {data && data.totalGroups === 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('logAnalyzer.duplicateRequests.noDuplicates')}
        </Alert>
      )}

      {/* Summary cards + filters + table */}
      {data && data.totalGroups > 0 && (
        <>
          <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap>
            <SummaryCard label={t('logAnalyzer.duplicateRequests.totalGroups')} value={data.totalGroups} />
            <SummaryCard label={t('logAnalyzer.duplicateRequests.totalDuplicateCalls')} value={data.totalDuplicateCalls} />
            <SummaryCard
              label={t('logAnalyzer.duplicateRequests.mostDuplicated')}
              value={data.mostDuplicatedEndpoint}
              subtitle={`${data.mostDuplicatedCount}x`}
            />
            <SummaryCard
              label={t('logAnalyzer.duplicateRequests.avgTimeBetween')}
              value={data.avgTimeBetweenDuplicatesMs > 0 ? formatDuration(data.avgTimeBetweenDuplicatesMs) : '-'}
            />
          </Stack>

          {/* Filters */}
          <Stack direction="row" spacing={2} mb={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <Autocomplete
              size="small"
              freeSolo
              options={endpointOptions}
              inputValue={filterEndpoint}
              onInputChange={(_, v) => { setFilterEndpoint(v ?? ''); setPage(0); setExpandedGroup(null) }}
              sx={{ minWidth: 260 }}
              renderInput={(params) => <TextField {...params} label={t('logAnalyzer.duplicateRequests.filterEndpoint')} />}
            />
            <TextField
              size="small"
              label={t('logAnalyzer.duplicateRequests.searchPayload')}
              value={filterPayload}
              onChange={(e) => { setFilterPayload(e.target.value); setPage(0); setExpandedGroup(null) }}
              slotProps={{ input: { startAdornment: <Search sx={{ mr: 0.5, fontSize: 18, color: 'text.secondary' }} /> } }}
              sx={{ minWidth: 220 }}
            />
            <Typography variant="body2" color="text.secondary">
              {filteredGroups.length} / {data.groups.length}
            </Typography>
          </Stack>

          {/* Groups table */}
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ ...headSx, width: 40 }} />
                  <TableCell sx={headSx}>
                    <TableSortLabel active={sort === 'endpoint'} direction={sort === 'endpoint' ? sortDir : 'asc'}
                      onClick={() => handleSort('endpoint')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.endpoint')}
                    </TableSortLabel>
                  </TableCell>
                  <TableCell sx={headSx}>{t('logAnalyzer.duplicateRequests.payloadPreview')}</TableCell>
                  <TableCell sx={headSx} align="right">
                    <TableSortLabel active={sort === 'count'} direction={sort === 'count' ? sortDir : 'desc'}
                      onClick={() => handleSort('count')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.count')}
                    </TableSortLabel>
                  </TableCell>
                  <TableCell sx={headSx}>
                    <TableSortLabel active={sort === 'firstSeen'} direction={sort === 'firstSeen' ? sortDir : 'asc'}
                      onClick={() => handleSort('firstSeen')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.firstSeen')}
                    </TableSortLabel>
                  </TableCell>
                  <TableCell sx={headSx}>
                    <TableSortLabel active={sort === 'lastSeen'} direction={sort === 'lastSeen' ? sortDir : 'asc'}
                      onClick={() => handleSort('lastSeen')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.lastSeen')}
                    </TableSortLabel>
                  </TableCell>
                  <TableCell sx={headSx} align="right">
                    <TableSortLabel active={sort === 'timeSpan'} direction={sort === 'timeSpan' ? sortDir : 'desc'}
                      onClick={() => handleSort('timeSpan')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.timeSpan')}
                    </TableSortLabel>
                  </TableCell>
                  <TableCell sx={headSx} align="right">
                    <TableSortLabel active={sort === 'avgGap'} direction={sort === 'avgGap' ? sortDir : 'desc'}
                      onClick={() => handleSort('avgGap')} sx={theadSortSx}>
                      {t('logAnalyzer.duplicateRequests.avgGap')}
                    </TableSortLabel>
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {paginatedGroups.map((group, idx) => {
                  const globalIdx = page * rowsPerPage + idx
                  return (
                    <GroupRow
                      key={globalIdx}
                      group={group}
                      globalIdx={globalIdx}
                      expanded={expandedGroup === globalIdx}
                      onToggle={toggleExpand}
                      onJumpToLine={onJumpToLine}
                      headSx={headSx}
                    />
                  )
                })}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={filteredGroups.length}
              page={page}
              onPageChange={(_, p) => { setPage(p); setExpandedGroup(null) }}
              rowsPerPage={rowsPerPage}
              onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0); setExpandedGroup(null) }}
              rowsPerPageOptions={[10, 25, 50, 100]}
            />
          </TableContainer>
        </>
      )}
    </Box>
  )
}

function SummaryCard({ label, value, subtitle }: { label: string; value: string | number; subtitle?: string }) {
  return (
    <Paper variant="outlined" sx={{ px: 2.5, py: 1.5, minWidth: 150 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" fontWeight={600} noWrap sx={{ maxWidth: 220 }}>
        {value}
      </Typography>
      {subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}
    </Paper>
  )
}

function PayloadDialog({ open, onClose, payload, endpoint }: {
  open: boolean; onClose: () => void; payload: string; endpoint: string
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const [copied, setCopied] = useState(false)

  const formatted = useMemo(() => {
    if (!payload || payload === '[empty]') return payload
    return tryFormatJson(payload).formatted
  }, [payload])

  const handleCopy = async () => {
    try {
      await copyToClipboard(formatted)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard write failed */ }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', pb: 1 }}>
        <Typography variant="subtitle1" fontWeight={600}>{endpoint}</Typography>
        <IconButton size="small" onClick={onClose}><Close /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Box sx={{
          position: 'relative', p: 2, borderRadius: 1, fontSize: '0.8rem',
          fontFamily: "'JetBrains Mono', monospace",
          bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.04)',
          whiteSpace: 'pre-wrap', wordBreak: 'break-all',
          overflow: 'auto', maxHeight: '60vh',
        }}>
          <Tooltip title={copied ? 'Copied!' : 'Copy'} arrow>
            <IconButton size="small" onClick={handleCopy}
              sx={{ position: 'absolute', top: 8, right: 8, opacity: 0.5, '&:hover': { opacity: 1 } }}>
              <ContentCopy sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
          {formatted}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('logAnalyzer.duplicateRequests.close')}</Button>
      </DialogActions>
    </Dialog>
  )
}

function GroupRow({ group, globalIdx, expanded, onToggle, onJumpToLine, headSx }: {
  group: DuplicateGroup
  globalIdx: number
  expanded: boolean
  onToggle: (index: number) => void
  onJumpToLine?: (line: number) => void
  headSx: Record<string, unknown>
}) {
  const { t } = useTranslation()
  const callsCapped = group.calls.length < group.occurrenceCount
  const [callsPage, setCallsPage] = useState(0)
  const [payloadOpen, setPayloadOpen] = useState(false)
  const callsPerPage = 20

  const paginatedCalls = useMemo(() => {
    const start = callsPage * callsPerPage
    return group.calls.slice(start, start + callsPerPage)
  }, [group.calls, callsPage])

  return (
    <>
      <TableRow
        hover
        sx={{ cursor: 'pointer', '& > *': { borderBottom: expanded ? 'unset' : undefined } }}
        onClick={() => onToggle(globalIdx)}
      >
        <TableCell>
          <IconButton size="small">
            {expanded ? <KeyboardArrowUp /> : <KeyboardArrowDown />}
          </IconButton>
        </TableCell>
        <TableCell>
          <Typography variant="body2" fontWeight={500}>{group.endpoint}</Typography>
        </TableCell>
        <TableCell>
          <Tooltip
            title={
              <Box sx={{ maxWidth: 500, maxHeight: 300, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem' }}>
                {group.payloadPreview}
              </Box>
            }
            arrow
            enterDelay={400}
            slotProps={{ tooltip: { sx: { maxWidth: 520, bgcolor: 'background.paper', color: 'text.primary', border: 1, borderColor: 'divider', p: 1.5 } } }}
          >
            <Typography
              variant="body2"
              fontFamily="'JetBrains Mono', monospace"
              fontSize="0.75rem"
              sx={{
                maxWidth: 350,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                cursor: 'help',
              }}
            >
              {group.payloadPreview}
            </Typography>
          </Tooltip>
        </TableCell>
        <TableCell align="right">
          <Chip label={group.occurrenceCount} size="small" color="warning" variant="outlined" />
        </TableCell>
        <TableCell>
          <Typography variant="body2" fontSize="0.8rem">
            {group.firstOccurrence ? formatTimestamp(group.firstOccurrence) : '-'}
          </Typography>
        </TableCell>
        <TableCell>
          <Typography variant="body2" fontSize="0.8rem">
            {group.lastOccurrence ? formatTimestamp(group.lastOccurrence) : '-'}
          </Typography>
        </TableCell>
        <TableCell align="right">
          <Typography variant="body2" fontSize="0.8rem">
            {group.timeSpanMs > 0 ? formatDuration(group.timeSpanMs) : '-'}
          </Typography>
        </TableCell>
        <TableCell align="right">
          <Typography variant="body2" fontSize="0.8rem">
            {group.avgTimeBetweenMs > 0 ? formatDuration(group.avgTimeBetweenMs) : '-'}
          </Typography>
        </TableCell>
      </TableRow>

      {/* Expanded detail */}
      <TableRow>
        <TableCell colSpan={8} sx={{ py: 0, px: 0 }}>
          <Collapse in={expanded} timeout="auto" unmountOnExit>
            <Box sx={{ px: 3, py: 1.5 }}>
              {/* Payload button + dialog */}
              {group.payloadPreview && group.payloadPreview !== '[empty]' && (
                <>
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<VisibilityOutlined />}
                    onClick={(e) => { e.stopPropagation(); setPayloadOpen(true) }}
                    sx={{ mb: 1.5 }}
                  >
                    {t('logAnalyzer.duplicateRequests.viewPayload')}
                  </Button>
                  <PayloadDialog
                    open={payloadOpen}
                    onClose={() => setPayloadOpen(false)}
                    payload={group.payloadPreview}
                    endpoint={group.endpoint}
                  />
                </>
              )}

              {callsCapped && (
                <Typography variant="caption" color="text.secondary" sx={{ mb: 1, display: 'block' }}>
                  {t('logAnalyzer.duplicateRequests.callsCapped', {
                    shown: group.calls.length,
                    total: group.occurrenceCount,
                  })}
                </Typography>
              )}
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell sx={headSx}>{t('logAnalyzer.duplicateRequests.thread')}</TableCell>
                    <TableCell sx={headSx}>{t('logAnalyzer.duplicateRequests.timestamp')}</TableCell>
                    <TableCell sx={headSx} align="right">{t('logAnalyzer.duplicateRequests.duration')}</TableCell>
                    <TableCell sx={headSx} align="center">{t('logAnalyzer.duplicateRequests.slow')}</TableCell>
                    <TableCell sx={headSx} align="right">{t('logAnalyzer.duplicateRequests.line')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {paginatedCalls.map((call, ci) => (
                    <TableRow key={ci} hover>
                      <TableCell>
                        <Typography variant="body2" fontSize="0.8rem">{call.thread}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontSize="0.8rem">
                          {call.requestTimestamp ? formatTimestamp(call.requestTimestamp) : '-'}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2" fontSize="0.8rem" fontFamily="'JetBrains Mono', monospace">
                          {formatDuration(call.durationMs)}
                        </Typography>
                      </TableCell>
                      <TableCell align="center">
                        {call.slow && <Chip label="slow" size="small" color="error" variant="outlined" />}
                      </TableCell>
                      <TableCell align="right">
                        {onJumpToLine && <LineLink line={call.requestLineNumber} onClick={onJumpToLine} />}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {group.calls.length > callsPerPage && (
                <TablePagination
                  component="div"
                  count={group.calls.length}
                  page={callsPage}
                  onPageChange={(_, p) => setCallsPage(p)}
                  rowsPerPage={callsPerPage}
                  rowsPerPageOptions={[callsPerPage]}
                  size="small"
                />
              )}
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  )
}

function formatTimestamp(ts: string): string {
  if (!ts) return '-'
  const tIndex = ts.indexOf('T')
  return tIndex >= 0 ? ts.substring(tIndex + 1) : ts
}
