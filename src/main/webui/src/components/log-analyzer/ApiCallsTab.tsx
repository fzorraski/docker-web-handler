import { useState, useEffect, useMemo, useCallback, Fragment } from 'react'
import { copyToClipboard } from '../../utils/clipboard'
import {
  Alert, Autocomplete, Box, Button, Collapse, Typography, Chip, IconButton, Tooltip,
  TextField, TablePagination, Switch, FormControlLabel, LinearProgress, Stack, Slider, ToggleButtonGroup, ToggleButton,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer, TableSortLabel,
  Menu, MenuItem, ListItemIcon, ListItemText,
  useTheme,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { AccessTime, Clear, ContentCopy, Download, Search, QueryStats, OpenInNew, Subject, FilterListOff, Tune } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { maskSensitiveFields, tryFormatJson } from '../../utils/jsonUtils'
import { formatDuration } from '../../utils/formatDuration'
import { formatBytes, formatLogTimestamp, formatLogTimestampShort } from '../../utils/format'
import { chartColor } from '../../utils/chartColors'
import { LineLink } from './LineLink'
import { ApiCallContextDialog } from './ApiCallContextDialog'
import type { ApiCallPair, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

function PayloadBox({ label, payload, sensitiveFields, maskEnabled, isDark, truncated, fullSize, onDownload }: {
  label: string; payload: string; sensitiveFields: string[]; maskEnabled: boolean; isDark: boolean
  truncated?: boolean; fullSize?: number; onDownload?: () => void
}) {
  const { t } = useTranslation()
  const formatted = useMemo(() => {
    const processed = maskSensitiveFields(payload, sensitiveFields, maskEnabled)
    return tryFormatJson(processed).formatted
  }, [payload, sensitiveFields, maskEnabled])
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await copyToClipboard(formatted)
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
      {truncated && (
        <Alert severity="info" sx={{ mt: 1, py: 0.5 }}
          action={onDownload && (
            <Button size="small" startIcon={<Download />} onClick={onDownload}>
              {t('logAnalyzer.apiCalls.downloadFullPayload')}
            </Button>
          )}>
          {t('logAnalyzer.apiCalls.payloadTruncated', { size: formatBytes(fullSize ?? 0) })}
        </Alert>
      )}
    </Box>
  )
}

const DURATION_SCALES = {
  ms: { max: 5000, step: 50, format: (v: number) => `${v}ms`, marks: [
    { value: 0, label: '0' }, { value: 500, label: '500' }, { value: 1000, label: '1000' },
    { value: 2000, label: '2000' }, { value: 3000, label: '3000' }, { value: 5000, label: '5000' },
  ] },
  s: { max: 60000, step: 500, format: (v: number) => `${(v / 1000).toFixed(1)}s`, marks: [
    { value: 0, label: '0' }, { value: 1000, label: '1s' }, { value: 5000, label: '5s' },
    { value: 15000, label: '15s' }, { value: 30000, label: '30s' }, { value: 60000, label: '60s' },
  ] },
  m: { max: 600000, step: 5000, format: (v: number) => `${(v / 60000).toFixed(1)}m`, marks: [
    { value: 0, label: '0' }, { value: 60000, label: '1m' }, { value: 120000, label: '2m' },
    { value: 300000, label: '5m' }, { value: 600000, label: '10m' },
  ] },
} as const

export function ApiCallsTab({ analysisId, sensitiveFields, timeRangeStart, timeRangeEnd, onJumpToLine, onJumpToRange, onViewInsights, orphanRequestCount, onGoToOrphans, initialTimeFrom, initialTimeTo, onTimeRangeConsumed }: {
  analysisId: string; sensitiveFields: string[]; timeRangeStart?: string; timeRangeEnd?: string
  onJumpToLine?: (line: number) => void; onJumpToRange?: (from: number, to: number) => void; onViewInsights?: (endpoint: string, timestamp: string) => void
  orphanRequestCount?: number; onGoToOrphans?: () => void
  initialTimeFrom?: string | null; initialTimeTo?: string | null; onTimeRangeConsumed?: () => void
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
  const debouncedEndpoint = useDebouncedValue(filterEndpoint, 300)
  const [filterThread, setFilterThread] = useState('')
  const [timeFrom, setTimeFrom] = useState<Dayjs | null>(null)
  const [timeTo, setTimeTo] = useState<Dayjs | null>(null)
  const timeFromIso = useMemo(() => timeFrom?.format('YYYY-MM-DDTHH:mm:ss') ?? '', [timeFrom])
  const timeToIso = useMemo(() => timeTo?.format('YYYY-MM-DDTHH:mm:ss') ?? '', [timeTo])
  const debouncedTimeFrom = useDebouncedValue(timeFromIso, 400)
  const debouncedTimeTo = useDebouncedValue(timeToIso, 400)
  const [timeFilterOpen, setTimeFilterOpen] = useState(false)
  const [contentSearch, setContentSearch] = useState('')
  const debouncedContentSearch = useDebouncedValue(contentSearch, 300)
  const [excludePatterns, setExcludePatterns] = useState('')
  const debouncedExclude = useDebouncedValue(excludePatterns, 300)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [slowOnly, setSlowOnly] = useState(false)
  const [durationScale, setDurationScale] = useState<'ms' | 's' | 'm'>('s')
  const [durationRange, setDurationRange] = useState<[number, number]>([0, 60000])
  const [durationEnabled, setDurationEnabled] = useState(false)
  const advancedActive = slowOnly || durationEnabled

  const scaleConfig = DURATION_SCALES[durationScale]

  const minDatetime = useMemo(() => timeRangeStart ? dayjs(timeRangeStart) : null, [timeRangeStart])
  const maxDatetime = useMemo(() => timeRangeEnd ? dayjs(timeRangeEnd) : null, [timeRangeEnd])
  const hasTimeRange = !!(timeRangeStart && timeRangeEnd)
  const timeFilterActive = !!(timeFrom || timeTo)

  const applyQuickRange = useCallback((from: Dayjs, to: Dayjs) => {
    setTimeFrom(from)
    setTimeTo(to)
    setPage(0)
  }, [])
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [maskEnabled, setMaskEnabled] = useState(true)
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; endpoint: string; timestamp: string } | null>(null)
  const [contextDialog, setContextDialog] = useState<{ from: number; to: number; endpoint: string } | null>(null)

  const handleDownloadPayload = useCallback(async (line: number, type: 'request' | 'response') => {
    try {
      const blob = await logService.downloadApiCallPayload(analysisId, line, type)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `payload-${type}-line${line}.txt`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      // download failed
    }
  }, [analysisId])

  useEffect(() => {
    logService.getEndpoints(analysisId).then(setEndpoints).catch(() => {})
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  // Apply time range when navigated from Performance Insights
  useEffect(() => {
    if (initialTimeFrom == null || initialTimeTo == null) return
    setTimeFrom(dayjs(initialTimeFrom))
    setTimeTo(dayjs(initialTimeTo))
    setTimeFilterOpen(true)
    setPage(0)
    onTimeRangeConsumed?.()
  }, [initialTimeFrom, initialTimeTo, onTimeRangeConsumed])

  const { data, total, loading } = usePaginatedFetch<ApiCallPair>(
    (signal) => logService.getApiCalls(analysisId, {
      endpoint: debouncedEndpoint || undefined,
      thread: filterThread || undefined,
      minDuration: durationEnabled ? durationRange[0] : undefined,
      maxDuration: durationEnabled ? durationRange[1] : undefined,
      slowOnly: slowOnly || undefined,
      search: debouncedContentSearch || undefined,
      exclude: debouncedExclude || undefined,
      timeFrom: debouncedTimeFrom || undefined,
      timeTo: debouncedTimeTo || undefined,
      sort, sortDir, page, size: rowsPerPage, signal,
    }),
    [analysisId, debouncedEndpoint, filterThread, durationEnabled, durationRange, slowOnly, debouncedContentSearch, debouncedExclude, debouncedTimeFrom, debouncedTimeTo, sort, sortDir, page, rowsPerPage],
  )

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={1} flexWrap="wrap" useFlexGap alignItems="center">
        <Autocomplete
          size="small"
          freeSolo
          sx={{ minWidth: 300 }}
          options={endpoints}
          inputValue={filterEndpoint}
          onInputChange={(_, v) => { setFilterEndpoint(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.filterEndpoint')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={threads.map((th) => th.thread)}
          value={filterThread || null}
          onChange={(_, v) => { setFilterThread(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.thread')} />}
        />
        <Tooltip title={t('logAnalyzer.apiCalls.timeFilter')} arrow>
          <Chip
            icon={<AccessTime sx={{ fontSize: 16 }} />}
            label={timeFilterActive
              ? `${timeFrom ? formatLogTimestampShort(timeFrom.format('YYYY-MM-DDTHH:mm:ss')) : '...'} — ${timeTo ? formatLogTimestampShort(timeTo.format('YYYY-MM-DDTHH:mm:ss')) : '...'}`
              : t('logAnalyzer.apiCalls.timeFilter')}
            size="small"
            color={timeFilterActive ? 'primary' : 'default'}
            variant={timeFilterActive ? 'filled' : 'outlined'}
            onClick={() => setTimeFilterOpen(o => !o)}
            onDelete={timeFilterActive ? () => { setTimeFrom(null); setTimeTo(null); setPage(0) } : undefined}
            sx={{ cursor: 'pointer', fontSize: '0.75rem', fontFamily: timeFilterActive ? "'JetBrains Mono', monospace" : undefined }}
          />
        </Tooltip>
        <Chip
          icon={<Tune sx={{ fontSize: 16 }} />}
          label={t('logAnalyzer.apiCalls.advancedFilters')}
          size="small"
          color={advancedActive ? 'secondary' : 'default'}
          variant={advancedActive ? 'filled' : 'outlined'}
          onClick={() => setAdvancedOpen(o => !o)}
          onDelete={advancedActive ? () => { setSlowOnly(false); setDurationEnabled(false); setPage(0) } : undefined}
          sx={{ cursor: 'pointer' }}
        />
        <FormControlLabel
          control={<Switch checked={maskEnabled} onChange={(_, v) => setMaskEnabled(v)} size="small" />}
          label={<Typography variant="body2">{t('logAnalyzer.apiCalls.maskSensitive')}</Typography>}
        />
      </Stack>
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
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
          sx={{ flex: 1, minWidth: 280 }}
        />
        <TextField
          size="small"
          placeholder={t('logAnalyzer.apiCalls.excludeContent')}
          value={excludePatterns}
          onChange={(e) => { setExcludePatterns(e.target.value); setPage(0) }}
          slotProps={{ input: {
            startAdornment: <FilterListOff sx={{ mr: 1, color: 'text.secondary', fontSize: 20 }} />,
            endAdornment: excludePatterns ? (
              <IconButton size="small" onClick={() => { setExcludePatterns(''); setPage(0) }} sx={{ p: 0.25 }}>
                <Clear sx={{ fontSize: 16 }} />
              </IconButton>
            ) : undefined,
          } }}
          sx={{ flex: 1, minWidth: 280 }}
        />
      </Stack>

      <Collapse in={timeFilterOpen}>
        <Box sx={{ mb: 2, p: 1.5, borderRadius: 1, bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)', border: '1px solid', borderColor: 'divider' }}>
          {hasTimeRange && (
            <Typography variant="caption" color="text.secondary" display="block" mb={1}>
              {t('logAnalyzer.apiCalls.logRange')}: {formatLogTimestamp(timeRangeStart)} — {formatLogTimestamp(timeRangeEnd)}
            </Typography>
          )}
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <MobileDateTimePicker
              label={t('logAnalyzer.apiCalls.timeFrom')}
              value={timeFrom}
              onChange={(v) => { setTimeFrom(v); setPage(0) }}
              minDateTime={minDatetime ?? undefined}
              maxDateTime={timeTo ?? maxDatetime ?? undefined}
              ampm={false}
              slotProps={{ textField: { size: 'small', sx: { minWidth: 220 } } }}
            />
            <MobileDateTimePicker
              label={t('logAnalyzer.apiCalls.timeTo')}
              value={timeTo}
              onChange={(v) => { setTimeTo(v); setPage(0) }}
              minDateTime={timeFrom ?? minDatetime ?? undefined}
              maxDateTime={maxDatetime ?? undefined}
              ampm={false}
              slotProps={{ textField: { size: 'small', sx: { minWidth: 220 } } }}
            />
            {hasTimeRange && (
              <>
                <Box sx={{ borderLeft: '1px solid', borderColor: 'divider', height: 24, mx: 0.5 }} />
                <Chip size="small" label={t('logAnalyzer.apiCalls.quickFirstHour')} variant="outlined"
                  onClick={() => applyQuickRange(minDatetime!, minDatetime!.add(1, 'hour').isAfter(maxDatetime!) ? maxDatetime! : minDatetime!.add(1, 'hour'))} />
                <Chip size="small" label={t('logAnalyzer.apiCalls.quickLastHour')} variant="outlined"
                  onClick={() => applyQuickRange(maxDatetime!.subtract(1, 'hour').isBefore(minDatetime!) ? minDatetime! : maxDatetime!.subtract(1, 'hour'), maxDatetime!)} />
                <Chip size="small" label={t('logAnalyzer.apiCalls.quickFirstHalf')} variant="outlined"
                  onClick={() => { const mid = minDatetime!.add(maxDatetime!.diff(minDatetime!) / 2, 'ms'); applyQuickRange(minDatetime!, mid) }} />
                <Chip size="small" label={t('logAnalyzer.apiCalls.quickLastHalf')} variant="outlined"
                  onClick={() => { const mid = minDatetime!.add(maxDatetime!.diff(minDatetime!) / 2, 'ms'); applyQuickRange(mid, maxDatetime!) }} />
                <Box sx={{ borderLeft: '1px solid', borderColor: 'divider', height: 24, mx: 0.5 }} />
                <Chip size="small" label={t('logAnalyzer.apiCalls.quickClear')} variant="outlined" color="default"
                  onDelete={() => { setTimeFrom(null); setTimeTo(null); setPage(0) }}
                  onClick={() => { setTimeFrom(null); setTimeTo(null); setPage(0) }} />
              </>
            )}
          </Stack>
        </Box>
      </Collapse>

      <Collapse in={advancedOpen}>
        <Box sx={{ mb: 2, p: 1.5, borderRadius: 1, bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)', border: '1px solid', borderColor: 'divider' }}>
          <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap alignItems="center">
            <FormControlLabel
              control={<Switch checked={slowOnly} onChange={(_, v) => { setSlowOnly(v); setPage(0) }} size="small" color="error" />}
              label={<Typography variant="body2" color={slowOnly ? 'error.main' : 'text.secondary'}>{t('logAnalyzer.apiCalls.slowOnly')}</Typography>}
            />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flex: 1, minWidth: 300 }}>
              <FormControlLabel
                control={<Switch checked={durationEnabled} onChange={(_, v) => { setDurationEnabled(v); setPage(0) }} size="small" />}
                label={<Typography variant="body2">{t('logAnalyzer.apiCalls.durationRange')}</Typography>}
                sx={{ mr: 0 }}
              />
              {durationEnabled && (
                <>
                  <ToggleButtonGroup
                    value={durationScale}
                    exclusive
                    onChange={(_, v) => { if (v) { setDurationScale(v); setDurationRange([0, v === 'ms' ? 5000 : v === 'm' ? 600000 : 60000]); setPage(0) } }}
                    size="small"
                  >
                    <ToggleButton value="ms" sx={{ px: 1.5, py: 0.25, fontSize: '0.75rem' }}>ms</ToggleButton>
                    <ToggleButton value="s" sx={{ px: 1.5, py: 0.25, fontSize: '0.75rem' }}>sec</ToggleButton>
                    <ToggleButton value="m" sx={{ px: 1.5, py: 0.25, fontSize: '0.75rem' }}>min</ToggleButton>
                  </ToggleButtonGroup>
                  <Box sx={{ flex: 1, px: 1 }}>
                    <Slider
                      value={durationRange.map(v => Math.min(v, scaleConfig.max)) as unknown as [number, number]}
                      onChange={(_, v) => setDurationRange(v as [number, number])}
                      onChangeCommitted={() => setPage(0)}
                      min={0}
                      max={scaleConfig.max}
                      step={scaleConfig.step}
                      valueLabelDisplay="auto"
                      valueLabelFormat={scaleConfig.format}
                      marks={scaleConfig.marks}
                      size="small"
                    />
                  </Box>
                </>
              )}
            </Box>
          </Stack>
        </Box>
      </Collapse>

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
                      <Typography variant="body2" fontSize="0.8rem">{formatLogTimestamp(call.requestTimestamp)}</Typography>
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
                          <PayloadBox label="Request" payload={call.requestPayload} sensitiveFields={sensitiveFields} maskEnabled={maskEnabled} isDark={isDark}
                            truncated={call.requestPayloadTruncated} fullSize={call.requestPayloadSize}
                            onDownload={() => handleDownloadPayload(call.requestLineNumber, 'request')} />
                          <PayloadBox label="Response" payload={call.responsePayload} sensitiveFields={sensitiveFields} maskEnabled={maskEnabled} isDark={isDark}
                            truncated={call.responsePayloadTruncated} fullSize={call.responsePayloadSize}
                            onDownload={() => handleDownloadPayload(call.requestLineNumber, 'response')} />
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
