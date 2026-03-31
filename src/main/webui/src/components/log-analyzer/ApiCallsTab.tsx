import { useState, useEffect, Fragment } from 'react'
import {
  Autocomplete, Box, Typography, Chip, IconButton, Tooltip,
  TextField, TablePagination, Switch, FormControlLabel, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  useTheme,
} from '@mui/material'
import { Clear, ContentCopy, Search } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { maskSensitiveFields, tryFormatJson } from '../../utils/jsonUtils'
import { formatDuration } from '../../utils/formatDuration'
import { LineLink } from './LineLink'
import type { ApiCallPair, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

const RAINBOW = [
  '#FF6D00', '#00BCD4', '#8BC34A', '#E91E63', '#FFC107',
  '#9C27B0', '#03A9F4', '#FF5722', '#4CAF50', '#673AB7',
]

function rainbowColor(index: number): string {
  return RAINBOW[index % RAINBOW.length]
}

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

export function ApiCallsTab({ analysisId, sensitiveFields, onJumpToLine }: {
  analysisId: string; sensitiveFields: string[]; onJumpToLine?: (line: number) => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [sort, setSort] = useState('time')
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [contentSearch, setContentSearch] = useState('')
  const debouncedContentSearch = useDebouncedValue(contentSearch, 300)
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [maskEnabled, setMaskEnabled] = useState(true)
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [threads, setThreads] = useState<ThreadInfo[]>([])

  useEffect(() => {
    logService.getEndpoints(analysisId).then(setEndpoints).catch(() => {})
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  const { data, total, loading } = usePaginatedFetch<ApiCallPair>(
    () => logService.getApiCalls(analysisId, {
      endpoint: filterEndpoint || undefined,
      thread: filterThread || undefined,
      search: debouncedContentSearch || undefined,
      sort, page, size: rowsPerPage,
    }),
    [analysisId, filterEndpoint, filterThread, debouncedContentSearch, sort, page, rowsPerPage],
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
        <Autocomplete
          size="small"
          sx={{ minWidth: 180 }}
          disableClearable
          options={[
            { value: 'time', label: t('logAnalyzer.apiCalls.sortByTime') },
            { value: 'duration', label: t('logAnalyzer.apiCalls.sortByDuration') },
            { value: 'endpoint', label: t('logAnalyzer.apiCalls.sortByEndpoint') },
          ]}
          value={{ value: sort, label: sort === 'duration' ? t('logAnalyzer.apiCalls.sortByDuration') : sort === 'endpoint' ? t('logAnalyzer.apiCalls.sortByEndpoint') : t('logAnalyzer.apiCalls.sortByTime') }}
          onChange={(_, v) => setSort(v?.value ?? 'time')}
          getOptionLabel={(o) => o.label}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.sort')} />}
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
              <TableCell>{t('logAnalyzer.apiCalls.endpoint')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.correlationId')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.requestTime')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.duration')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.map((call, idx) => {
              const globalIdx = page * rowsPerPage + idx
              const color = rainbowColor(globalIdx)
              return (
                <Fragment key={globalIdx}>
                  <TableRow hover onClick={() => setExpandedRow(expandedRow === globalIdx ? null : globalIdx)}
                    sx={{ cursor: 'pointer' }}>
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
                          <Box sx={{ mb: 1, display: 'flex', gap: 2 }}>
                            <Typography variant="caption" component="span">
                              Request <LineLink line={call.requestLineNumber} onClick={onJumpToLine} />
                            </Typography>
                            <Typography variant="caption" component="span">
                              Response <LineLink line={call.responseLineNumber} onClick={onJumpToLine} />
                            </Typography>
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
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        showFirstButton showLastButton />
    </Box>
  )
}
