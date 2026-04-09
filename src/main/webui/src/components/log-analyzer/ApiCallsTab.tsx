import { useState, useEffect, Fragment } from 'react'
import {
  Autocomplete, Box, Typography, Chip, IconButton, Tooltip,
  TextField, TablePagination, Switch, FormControlLabel, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer, TableSortLabel,
  Menu, MenuItem, ListItemIcon, ListItemText,
  useTheme,
} from '@mui/material'
import { Clear, ContentCopy, Search, QueryStats, OpenInNew, Subject } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { maskSensitiveFields, tryFormatJson } from '../../utils/jsonUtils'
import { formatDuration } from '../../utils/formatDuration'
import { chartColor } from '../../utils/chartColors'
import { LineLink } from './LineLink'
import { ApiCallContextDialog } from './ApiCallContextDialog'
import type { ApiCallPair, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

function PayloadBox({ label, payload, sensitiveFields, maskEnabled, isDark }: {
  label: string; payload: string; sensitiveFields: string[]; maskEnabled: boolean; isDark: boolean
}) {
  const processed = maskSensitiveFields(payload, sensitiveFields, maskEnabled)
  const { formatted } = tryFormatJson(processed)
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(formatted)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // clipboard write failed
    }
  }

  return (
    <Box sx={{ flex: 1, minWidth: 300, minHeight: 0, overflow: 'hidden' }}>
      <Typography variant="caption" fontWeight={700} color="text.secondary">{label}</Typography>
      <Box sx={{
        position: 'relative', p: 1.5, borderRadius: 1, fontSize: '0.78rem', fontFamily: "'JetBrains Mono', monospace",
        bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.04)',
        whiteSpace: 'pre-wrap', wordBreak: 'break-all', overflowX: 'auto', maxHeight: 400,
      }}>
        <Tooltip title={copied ? 'Copied!' : 'Copy'} arrow>
          <IconButton size="small" onClick={handleCopy}
            sx={{ position: 'absolute', top: 4, right: 4, opacity: 0.4, '&:hover': { opacity: 1 } }}>
            <ContentCopy sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
        {formatted}
      </Box>
    </Box>
  )
}

export function ApiCallsTab({ analysisId, sensitiveFields, onJumpToLine, onJumpToRange, onViewInsights, orphanRequestCount, onGoToOrphans }: {
  analysisId: string; sensitiveFields: string[]; onJumpToLine?: (line: number) => void; onJumpToRange?: (from: number, to: number) => void; onViewInsights?: (endpoint: string, timestamp: string) => void
  orphanRequestCount?: number; onGoToOrphans?: () => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [sort, setSort] = useState('time')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')

  const handleSort = (field: string, defaultDir: 'asc' | 'desc' = 'asc') => {
    if (sort === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSort(field); setSortDir(defaultDir) }
    setPage(0)
  }
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [contentSearch, setContentSearch] = useState('')
  const debouncedContentSearch = useDebouncedValue(contentSearch, 300)
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [maskEnabled, setMaskEnabled] = useState(true)
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; endpoint: string; timestamp: string } | null>(null)
  const [contextDialog, setContextDialog] = useState<{ from: number; to: number; endpoint: string } | null>(null)

  useEffect(() => {
    logService.getEndpoints(analysisId).then(setEndpoints).catch(() => {})
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  const { data, total, loading } = usePaginatedFetch<ApiCallPair>(
    () => logService.getApiCalls(analysisId, {
      endpoint: filterEndpoint || undefined,
      thread: filterThread || undefined,
      search: debouncedContentSearch || undefined,
      sort, sortDir, page, size: rowsPerPage,
    }),
    [analysisId, filterEndpoint, filterThread, debouncedContentSearch, sort, sortDir, page, rowsPerPage],
  )

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={endpoints}
          value={filterEndpoint || null}
          onChange={(_, v) => { setFilterEndpoint(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.endpoint')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={threads.map((th) => th.thread)}
          value={filterThread || null}
          onChange={(_, v) => { setFilterThread(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.thread')} />}
        />
        <TextField
          size="small"
          placeholder={t('logAnalyzer.apiCalls.searchContent')}
          value={contentSearch}
          onChange={(e) => { setContentSearch(e.target.value); setPage(0) }}
          slotProps={{ input: {
            startAdornment: <Search sx={{ mr: 1, color: 'text.secondary', fontSize: 20 }} />,
            endAdornment: contentSearch ? (
              <IconButton size="small" onClick={() => { setContentSearch(''); setPage(0) }} sx={{ p: 0.25 }}>
                <Clear sx={{ fontSize: 16 }} />
              </IconButton>
            ) : undefined,
          } }}
          sx={{ minWidth: 280 }}
        />
        <FormControlLabel
          control={<Switch checked={maskEnabled} onChange={(_, v) => setMaskEnabled(v)} size="small" />}
          label={<Typography variant="body2">{t('logAnalyzer.apiCalls.maskSensitive')}</Typography>}
        />
      </Stack>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell width={30}></TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'endpoint'} direction={sort === 'endpoint' ? sortDir : 'asc'}
                  onClick={() => handleSort('endpoint')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.apiCalls.endpoint')}
                </TableSortLabel>
              </TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.correlationId')}</TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'thread'} direction={sort === 'thread' ? sortDir : 'asc'}
                  onClick={() => handleSort('thread')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.apiCalls.thread')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'time'} direction={sort === 'time' ? sortDir : 'asc'}
                  onClick={() => handleSort('time')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.apiCalls.requestTime')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'duration'} direction={sort === 'duration' ? sortDir : 'desc'}
                  onClick={() => handleSort('duration', 'desc')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.apiCalls.duration')}
                </TableSortLabel>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.map((call, idx) => {
              const globalIdx = page * rowsPerPage + idx
              const color = chartColor(globalIdx)
              return (
                <Fragment key={globalIdx}>
                  <TableRow hover onClick={() => setExpandedRow(expandedRow === globalIdx ? null : globalIdx)}
                    sx={{ cursor: 'pointer' }}
                    onContextMenu={onViewInsights && call.requestTimestamp ? (e) => { e.preventDefault(); setContextMenu({ x: e.clientX, y: e.clientY, endpoint: call.endpoint, timestamp: call.requestTimestamp! }) } : undefined}>
                    <TableCell sx={{ px: 0.5 }}>
                      <Box sx={{ width: 4, height: 24, borderRadius: 2, bgcolor: color }} />
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{call.endpoint}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" color="text.secondary" fontSize="0.8rem">{call.correlationId ?? '-'}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">{call.thread}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">{call.requestTimestamp?.replace('T', ' ')}</Typography>
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={formatDuration(call.durationMs)}
                        color={call.slow ? 'error' : 'default'}
                        variant={call.slow ? 'filled' : 'outlined'}
                        sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem' }}
                      />
                    </TableCell>
                  </TableRow>
                  {expandedRow === globalIdx && (
                    <TableRow>
                      <TableCell colSpan={6} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)', borderLeft: `3px solid ${color}` }}>
                        {onJumpToLine && (
                          <Box sx={{ mb: 1, display: 'flex', gap: 2, alignItems: 'center' }}>
                            <Typography variant="caption" component="span">
                              Request <LineLink line={call.requestLineNumber} onClick={onJumpToLine} />
                            </Typography>
                            <Typography variant="caption" component="span">
                              Response <LineLink line={call.responseLineNumber} onClick={onJumpToLine} />
                            </Typography>
                            <Box sx={{ display: 'flex', gap: 1 }}>
                              {onJumpToRange && (
                                <Tooltip title={t('logAnalyzer.apiCalls.viewInRawLog')}>
                                  <IconButton size="small" onClick={(e) => { e.stopPropagation(); onJumpToRange(call.requestLineNumber, call.responseLineNumber) }}>
                                    <OpenInNew fontSize="small" />
                                  </IconButton>
                                </Tooltip>
                              )}
                              <Tooltip title={t('logAnalyzer.apiCalls.viewContext')}>
                                <IconButton size="small" onClick={(e) => { e.stopPropagation(); setContextDialog({ from: call.requestLineNumber, to: call.responseLineNumber, endpoint: call.endpoint }) }}>
                                  <Subject fontSize="small" />
                                </IconButton>
                              </Tooltip>
                            </Box>
                          </Box>
                        )}
                        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                          <PayloadBox label="Request" payload={call.requestPayload} sensitiveFields={sensitiveFields} maskEnabled={maskEnabled} isDark={isDark} />
                          <PayloadBox label="Response" payload={call.responsePayload} sensitiveFields={sensitiveFields} maskEnabled={maskEnabled} isDark={isDark} />
                        </Box>
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              )
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <Stack direction="row" alignItems="center">
        {orphanRequestCount != null && orphanRequestCount > 0 && onGoToOrphans && (
          <Chip
            size="small"
            label={`${orphanRequestCount} ${t('logAnalyzer.apiCalls.orphanRequests')}`}
            color="warning"
            variant="outlined"
            onClick={onGoToOrphans}
            sx={{ cursor: 'pointer', fontSize: '0.75rem', ml: 1 }}
          />
        )}
        <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
          rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
          showFirstButton showLastButton sx={{ flex: 1 }} />
      </Stack>

      {onViewInsights && (
        <Menu
          open={contextMenu !== null}
          onClose={() => setContextMenu(null)}
          anchorReference="anchorPosition"
          anchorPosition={contextMenu ? { top: contextMenu.y, left: contextMenu.x } : undefined}
        >
          <MenuItem onClick={() => { if (contextMenu) onViewInsights(contextMenu.endpoint, contextMenu.timestamp); setContextMenu(null) }}>
            <ListItemIcon><QueryStats fontSize="small" /></ListItemIcon>
            <ListItemText>{t('logAnalyzer.insights.viewPerformance')}</ListItemText>
          </MenuItem>
        </Menu>
      )}

      <ApiCallContextDialog
        open={contextDialog !== null}
        onClose={() => setContextDialog(null)}
        analysisId={analysisId}
        from={contextDialog?.from ?? 0}
        to={contextDialog?.to ?? 0}
        endpoint={contextDialog?.endpoint ?? ''}
      />
    </Box>
  )
}
