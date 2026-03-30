import { useState, useEffect, useCallback, useMemo, useRef, Fragment } from 'react'
import {
  Autocomplete, Box, Typography, Button, Paper, Tabs, Tab, Table, TableHead,
  TableRow, TableCell, TableBody, Chip, IconButton, Tooltip,
  TextField, Collapse,
  TablePagination, Switch, FormControlLabel, LinearProgress, Stack,
  TableContainer,
  useTheme,
} from '@mui/material'
import {
  Clear, CloudUpload, ContentCopy, ExpandMore,
  Search, MergeType,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNotification } from '../components/NotificationProvider'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import type {
  AnalysisSummary, ApiCallPair, EndpointStats, LogLine, JobExecution,
  RepeatedFailure, ThreadInfo, LogPreset, UploadOptions,
} from '../services/logAnalyzerService'
import * as logService from '../services/logAnalyzerService'

const RAINBOW = [
  '#FF6D00', '#00BCD4', '#8BC34A', '#E91E63', '#FFC107',
  '#9C27B0', '#03A9F4', '#FF5722', '#4CAF50', '#673AB7',
]

function rainbowColor(index: number): string {
  return RAINBOW[index % RAINBOW.length]
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1048576).toFixed(1)} MB`
}

function maskSensitiveFields(json: string, fields: string[], enabled: boolean): string {
  if (!enabled || fields.length === 0) return json
  try {
    const obj = JSON.parse(json)
    const mask = (o: unknown): unknown => {
      if (typeof o !== 'object' || o === null) return o
      if (Array.isArray(o)) return o.map(mask)
      const result: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
        result[k] = fields.some(f => k.toLowerCase() === f.toLowerCase())
          ? '***'
          : typeof v === 'object' ? mask(v) : v
      }
      return result
    }
    return JSON.stringify(mask(obj), null, 2)
  } catch {
    return json
  }
}

function tryFormatJson(text: string): { formatted: string; isJson: boolean } {
  try {
    const obj = JSON.parse(text)
    return { formatted: JSON.stringify(obj, null, 2), isJson: true }
  } catch {
    return { formatted: text, isJson: false }
  }
}

// ---- Main Page ----

export default function LogAnalyzerPage() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [presets, setPresets] = useState<LogPreset[]>([])
  const [defaultPreset, setDefaultPreset] = useState('WILDFLY')
  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [activeTab, setActiveTab] = useState(0)

  // Upload form
  const [selectedPreset, setSelectedPreset] = useState('')
  const [slowThreshold, setSlowThreshold] = useState(1000)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [customRegex, setCustomRegex] = useState<Partial<UploadOptions>>({})

  // Compose
  const [composeIds, setComposeIds] = useState<Set<string>>(new Set())

  useEffect(() => {
    logService.getStatus().then((s) => {
      setPresets(s.presets)
      setDefaultPreset(s.defaultPreset)
      setSelectedPreset(s.defaultPreset)
    }).catch(() => {})
    refreshList()
  }, [])

  // Reset tab index when selectedId changes
  useEffect(() => {
    setActiveTab(0)
  }, [selectedId])

  const refreshList = useCallback(() => {
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  const selected = useMemo(
    () => analyses.find((a) => a.id === selectedId) ?? null,
    [analyses, selectedId],
  )

  const handleUpload = useCallback(async (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return
    setUploading(true)
    try {
      const files = Array.from(fileList)
      const currentPreset = presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase())
      const opts: UploadOptions = {
        preset: selectedPreset,
        slowThresholdMs: slowThreshold,
        ...customRegex,
      }
      if (currentPreset && customRegex.logLineRegex && customRegex.logLineRegex !== currentPreset.logLineRegex) {
        opts.logLineRegex = customRegex.logLineRegex
      }
      const result = await logService.uploadFiles(files, opts)
      setSelectedId(result.id)
      refreshList()
      notify(t('logAnalyzer.upload.success'), 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.upload.error'), 'error')
    } finally {
      setUploading(false)
    }
  }, [selectedPreset, slowThreshold, customRegex, presets, refreshList, notify, t])

  const handleDelete = useCallback(async (id: string) => {
    try {
      await logService.deleteAnalysis(id)
      if (selectedId === id) setSelectedId(null)
      refreshList()
    } catch (err) {
      notify(err instanceof Error ? err.message : String(err), 'error')
    }
  }, [selectedId, refreshList, notify, t])

  const handleCompose = useCallback(async () => {
    if (composeIds.size < 2) return
    try {
      const result = await logService.composeAnalyses(Array.from(composeIds), selectedPreset, slowThreshold)
      setSelectedId(result.id)
      setComposeIds(new Set())
      refreshList()
      notify(t('logAnalyzer.compose.success'), 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.compose.error'), 'error')
    }
  }, [composeIds, selectedPreset, slowThreshold, refreshList, notify, t])

  const presetObj = useMemo(
    () => presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()),
    [presets, selectedPreset],
  )

  return (
    <Box sx={{ maxWidth: 1600, mx: 'auto', p: 3 }}>
      <Typography variant="h4" fontWeight={700} mb={3}>
        {t('logAnalyzer.title')}
      </Typography>

      {/* ---- Upload Panel ---- */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="flex-start">
          <Button
            variant="contained"
            component="label"
            startIcon={<CloudUpload />}
            disabled={uploading}
          >
            {t('logAnalyzer.upload.selectFiles')}
            <input type="file" hidden multiple accept=".log,.txt,.out" onChange={(e) => handleUpload(e.target.files)} />
          </Button>
          <Autocomplete
            size="small"
            sx={{ minWidth: 200 }}
            disableClearable
            options={presets}
            getOptionLabel={(p) => p.name}
            value={presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()) ?? presets[0] ?? undefined}
            onChange={(_, p) => {
              if (!p) return
              setSelectedPreset(p.name.toUpperCase())
              setCustomRegex({
                logLineRegex: p.logLineRegex,
                apiCallRegex: p.apiCallRegex ?? undefined,
                timestampFormat: p.timestampFormat,
                jobStartRegex: p.jobStartRegex ?? undefined,
                jobEndRegex: p.jobEndRegex ?? undefined,
                failureRegex: p.failureRegex ?? undefined,
                sensitiveFieldNames: p.sensitiveFieldNames?.join(','),
              })
            }}
            isOptionEqualToValue={(o, v) => o.name === v.name}
            renderInput={(params) => <TextField {...params} label={t('logAnalyzer.upload.preset')} />}
          />
          <TextField
            size="small"
            label={t('logAnalyzer.upload.slowThreshold')}
            type="number"
            value={slowThreshold}
            onChange={(e) => setSlowThreshold(Number(e.target.value))}
            sx={{ width: 130 }}
            slotProps={{ htmlInput: { min: 0 } }}
          />
          <Button size="small" onClick={() => setAdvancedOpen(!advancedOpen)} endIcon={<ExpandMore />}>
            {t('logAnalyzer.upload.advanced')}
          </Button>
        </Stack>
        {uploading && <LinearProgress sx={{ mt: 2 }} />}

        <Collapse in={advancedOpen}>
          <Stack spacing={2} sx={{ mt: 2 }}>
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.logLineRegex')}
              value={customRegex.logLineRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, logLineRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.apiCallRegex')}
              value={customRegex.apiCallRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, apiCallRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.timestampFormat')}
              value={customRegex.timestampFormat ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, timestampFormat: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.jobStartRegex')}
              value={customRegex.jobStartRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, jobStartRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.jobEndRegex')}
              value={customRegex.jobEndRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, jobEndRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.failureRegex')}
              value={customRegex.failureRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, failureRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.sensitiveFields')}
              value={customRegex.sensitiveFieldNames ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, sensitiveFieldNames: e.target.value }))}
              helperText={t('logAnalyzer.upload.sensitiveFieldsHelp')} />
          </Stack>
        </Collapse>

        {/* File list */}
        {analyses.length > 0 && (
          <Box sx={{ mt: 2 }}>
            <Stack direction="row" alignItems="center" spacing={1} mb={1}>
              <Typography variant="subtitle2">{t('logAnalyzer.upload.analyses')}</Typography>
              {composeIds.size >= 2 && (
                <Button size="small" startIcon={<MergeType />} onClick={handleCompose}>
                  {t('logAnalyzer.compose.button')} ({composeIds.size})
                </Button>
              )}
            </Stack>
            {analyses.map((a) => (
              <Chip
                key={a.id}
                label={`${a.sourceFiles.map(f => f.filename).join(', ')} (${a.totalLineCount.toLocaleString()} lines)`}
                onClick={() => setSelectedId(a.id)}
                onDelete={() => handleDelete(a.id)}
                variant={selectedId === a.id ? 'filled' : 'outlined'}
                color={selectedId === a.id ? 'primary' : 'default'}
                sx={{ mr: 1, mb: 1, cursor: 'pointer' }}
                icon={
                  <input
                    type="checkbox"
                    checked={composeIds.has(a.id)}
                    onChange={(e) => {
                      e.stopPropagation()
                      setComposeIds(prev => {
                        const next = new Set(prev)
                        if (next.has(a.id)) next.delete(a.id)
                        else next.add(a.id)
                        return next
                      })
                    }}
                    onClick={(e) => e.stopPropagation()}
                    style={{ marginLeft: 8, cursor: 'pointer' }}
                  />
                }
              />
            ))}
          </Box>
        )}
      </Paper>

      {/* ---- Dashboard ---- */}
      {selected && (
        <>
          <Stack direction="row" spacing={2} mb={3} flexWrap="wrap" useFlexGap>
            <SummaryCard label={t('logAnalyzer.dashboard.totalLines')} value={selected.totalLineCount.toLocaleString()} />
            <SummaryCard label={t('logAnalyzer.dashboard.apiCalls')} value={selected.apiCallCount.toLocaleString()} />
            <SummaryCard label={t('logAnalyzer.dashboard.threads')} value={selected.threadCount} />
            <SummaryCard label={t('logAnalyzer.dashboard.endpoints')} value={selected.endpointCount} />
            <SummaryCard label={t('logAnalyzer.dashboard.errors')} value={selected.errorCount} color="error.main" />
            {selected.jobExecutionCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.jobs')} value={selected.jobExecutionCount} />
            )}
            {selected.repeatedFailureCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.failures')} value={selected.repeatedFailureCount} color="warning.main" />
            )}
          </Stack>

          {/* Level counts */}
          <Stack direction="row" spacing={1} mb={3} flexWrap="wrap" useFlexGap>
            {Object.entries(selected.levelCounts).map(([level, count]) => (
              <Chip key={level} label={`${level}: ${count.toLocaleString()}`} size="small"
                color={level === 'ERROR' || level === 'FATAL' || level === 'SEVERE' ? 'error' : level === 'WARNING' || level === 'WARN' ? 'warning' : 'default'} />
            ))}
          </Stack>

          {/* ---- Tabs ---- */}
          <Paper>
            <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)} variant="scrollable" scrollButtons="auto">
              <Tab label={t('logAnalyzer.tabs.apiCalls')} />
              <Tab label={t('logAnalyzer.tabs.endpointStats')} />
              <Tab label={t('logAnalyzer.tabs.rawLog')} />
              <Tab label={t('logAnalyzer.tabs.threadView')} />
              {selected.jobExecutionCount > 0 && <Tab label={t('logAnalyzer.tabs.jobs')} />}
              {selected.repeatedFailureCount > 0 && <Tab label={t('logAnalyzer.tabs.failures')} />}
            </Tabs>
            <Box sx={{ p: 2 }}>
              {activeTab === 0 && <ApiCallsTab analysisId={selected.id} sensitiveFields={presetObj?.sensitiveFieldNames ?? []} isDark={isDark} headerTheme={headerTheme} />}
              {activeTab === 1 && <EndpointStatsTab analysisId={selected.id} isDark={isDark} headerTheme={headerTheme} />}
              {activeTab === 2 && <RawLogTab analysisId={selected.id} isDark={isDark} headerTheme={headerTheme} />}
              {activeTab === 3 && <ThreadViewTab analysisId={selected.id} isDark={isDark} headerTheme={headerTheme} />}
              {activeTab === 4 && selected.jobExecutionCount > 0 && <JobsTab analysisId={selected.id} isDark={isDark} headerTheme={headerTheme} />}
              {activeTab === (selected.jobExecutionCount > 0 ? 5 : 4) && selected.repeatedFailureCount > 0 && <FailuresTab analysisId={selected.id} isDark={isDark} headerTheme={headerTheme} />}
            </Box>
          </Paper>
        </>
      )}
    </Box>
  )
}

// ---- Summary Card ----

function SummaryCard({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <Paper sx={{ px: 2.5, py: 1.5, minWidth: 120 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" fontWeight={700} color={color}>{value}</Typography>
    </Paper>
  )
}

// ---- API Calls Tab ----

function ApiCallsTab({ analysisId, sensitiveFields, isDark, headerTheme }: {
  analysisId: string; sensitiveFields: string[]; isDark: boolean; headerTheme: { theadBg: string; theadColor: string }
}) {
  const { t } = useTranslation()
  const [data, setData] = useState<ApiCallPair[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [sort, setSort] = useState('time')
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [contentSearch, setContentSearch] = useState('')
  const [debouncedContentSearch, setDebouncedContentSearch] = useState('')
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [maskEnabled, setMaskEnabled] = useState(true)
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [loading, setLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedContentSearch(contentSearch), 300)
    return () => clearTimeout(timer)
  }, [contentSearch])

  useEffect(() => {
    logService.getEndpoints(analysisId).then(setEndpoints).catch(() => {})
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getApiCalls(analysisId, {
      endpoint: filterEndpoint || undefined,
      thread: filterThread || undefined,
      search: debouncedContentSearch || undefined,
      sort, page, size: rowsPerPage,
    }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setData(r.data)
      setTotal(r.total)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId, filterEndpoint, filterThread, debouncedContentSearch, sort, page, rowsPerPage])

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

// ---- Endpoint Stats Tab ----

function EndpointStatsTab({ analysisId, isDark, headerTheme }: { analysisId: string; isDark: boolean; headerTheme: { theadBg: string; theadColor: string } }) {
  const { t } = useTranslation()
  const [stats, setStats] = useState<EndpointStats[]>([])
  const [sortField, setSortField] = useState<keyof EndpointStats>('callCount')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading(true)
    logService.getApiStats(analysisId).then(setStats).catch(() => {}).finally(() => setLoading(false))
  }, [analysisId])

  const sorted = useMemo(() => {
    return [...stats].sort((a, b) => {
      const av = a[sortField] as number, bv = b[sortField] as number
      return sortDir === 'desc' ? bv - av : av - bv
    })
  }, [stats, sortField, sortDir])

  const maxAvg = useMemo(() => Math.max(...stats.map(s => s.avgDurationMs), 1), [stats])

  const handleSort = (field: keyof EndpointStats) => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortField(field); setSortDir('desc') }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('endpoint')}>{t('logAnalyzer.stats.endpoint')}</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('callCount')}>{t('logAnalyzer.stats.count')}</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('avgDurationMs')}>{t('logAnalyzer.stats.avg')}</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('minDurationMs')}>{t('logAnalyzer.stats.min')}</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('maxDurationMs')}>{t('logAnalyzer.stats.max')}</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('p95DurationMs')}>P95</TableCell>
              <TableCell sx={{ cursor: 'pointer' }} onClick={() => handleSort('slowCount')}>{t('logAnalyzer.stats.slow')}</TableCell>
              <TableCell width="20%"></TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sorted.map((s) => (
              <TableRow key={s.endpoint} hover>
                <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{s.endpoint}</Typography></TableCell>
                <TableCell>{s.callCount}</TableCell>
                <TableCell>{formatDuration(Math.round(s.avgDurationMs))}</TableCell>
                <TableCell>{formatDuration(s.minDurationMs)}</TableCell>
                <TableCell>{formatDuration(s.maxDurationMs)}</TableCell>
                <TableCell>{formatDuration(s.p95DurationMs)}</TableCell>
                <TableCell>{s.slowCount > 0 ? <Chip size="small" label={s.slowCount} color="error" /> : 0}</TableCell>
                <TableCell>
                  <Box sx={{ height: 8, borderRadius: 4, bgcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)' }}>
                    <Box sx={{ height: '100%', borderRadius: 4, bgcolor: 'primary.main', width: `${(s.avgDurationMs / maxAvg) * 100}%`, transition: 'width 0.3s' }} />
                  </Box>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  )
}

// ---- Raw Log Tab ----

function RawLogTab({ analysisId, isDark, headerTheme, initialThread }: { analysisId: string; isDark: boolean; headerTheme: { theadBg: string; theadColor: string }; initialThread?: string }) {
  const { t } = useTranslation()
  const [lines, setLines] = useState<LogLine[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(100)
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [filterLevel, setFilterLevel] = useState('')
  const [filterThread, setFilterThread] = useState(initialThread ?? '')
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [loading, setLoading] = useState(false)
  const fetchGenRef = useRef(0)

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search)
    }, 300)
    return () => clearTimeout(timer)
  }, [search])

  useEffect(() => {
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getLines(analysisId, {
      thread: filterThread || undefined,
      level: filterLevel || undefined,
      search: debouncedSearch || undefined,
      page, size: rowsPerPage,
    }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setLines(r.data)
      setTotal(r.total)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId, filterThread, filterLevel, debouncedSearch, page, rowsPerPage, initialThread])

  const levelColor = (level: string | null) => {
    switch (level) {
      case 'ERROR': case 'FATAL': case 'SEVERE': return '#FF5252'
      case 'WARN': case 'WARNING': return '#FFAB00'
      case 'INFO': return isDark ? '#00E676' : '#2e7d32'
      case 'DEBUG': return '#FF6D00'
      case 'TRACE': return '#7A8494'
      default: return isDark ? '#E8ECF1' : '#424242'
    }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <TextField size="small" placeholder={t('logAnalyzer.rawLog.search')} value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0) }}
          slotProps={{ input: { startAdornment: <Search sx={{ mr: 1, color: 'text.secondary' }} /> } }}
          sx={{ minWidth: 250 }} />
        <Autocomplete
          size="small"
          sx={{ minWidth: 160 }}
          options={['INFO', 'WARN', 'WARNING', 'ERROR', 'DEBUG', 'TRACE']}
          value={filterLevel || null}
          onChange={(_, v) => { setFilterLevel(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.rawLog.level')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 280 }}
          options={threads}
          getOptionLabel={(th) => `${th.thread} (${th.lineCount.toLocaleString()})`}
          value={threads.find((th) => th.thread === filterThread) ?? null}
          onChange={(_, v) => { setFilterThread(v?.thread ?? ''); setPage(0) }}
          isOptionEqualToValue={(o, v) => o.thread === v.thread}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.rawLog.thread')} />}
        />
      </Stack>

      <Box sx={{
        fontFamily: "'JetBrains Mono', monospace", fontSize: '0.78rem', lineHeight: 1.6,
        bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.03)',
        borderRadius: 1, p: 1, maxHeight: 600, overflowY: 'auto',
      }}>
        {lines.map((line) => (
          <Box key={`${line.sourceFile}-${line.lineNumber}`} sx={{ display: 'flex', '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' } }}>
            <Box sx={{ color: 'text.secondary', minWidth: 60, textAlign: 'right', pr: 1.5, userSelect: 'none', opacity: 0.5 }}>
              {line.lineNumber}
            </Box>
            <Box sx={{ color: levelColor(line.level), whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1 }}>
              {line.message ?? ''}
            </Box>
          </Box>
        ))}
      </Box>
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        rowsPerPageOptions={[50, 100, 200, 500]}
        showFirstButton showLastButton />
    </Box>
  )
}

// ---- Thread View Tab ----

function ThreadViewTab({ analysisId, isDark, headerTheme }: { analysisId: string; isDark: boolean; headerTheme: { theadBg: string; theadColor: string } }) {
  const { t } = useTranslation()
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [selectedThread, setSelectedThread] = useState('')

  useEffect(() => {
    logService.getThreads(analysisId).then((th) => {
      setThreads(th)
      if (th.length > 0 && !selectedThread) setSelectedThread(th[0].thread)
    }).catch(() => {})
  }, [analysisId])

  return (
    <Box>
      <Autocomplete
        size="small"
        sx={{ minWidth: 350, mb: 2 }}
        options={threads}
        getOptionLabel={(th) => `${th.thread} (${th.lineCount.toLocaleString()} ${t('logAnalyzer.common.lines')})`}
        value={threads.find((th) => th.thread === selectedThread) ?? null}
        onChange={(_, v) => setSelectedThread(v?.thread ?? '')}
        isOptionEqualToValue={(o, v) => o.thread === v.thread}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.threads.select')} />}
      />
      {selectedThread && <RawLogTab key={selectedThread} analysisId={analysisId} isDark={isDark} headerTheme={headerTheme} initialThread={selectedThread} />}
    </Box>
  )
}

// ---- Jobs Tab ----

function JobsTab({ analysisId, isDark, headerTheme }: { analysisId: string; isDark: boolean; headerTheme: { theadBg: string; theadColor: string } }) {
  const { t } = useTranslation()
  const [jobs, setJobs] = useState<JobExecution[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [loading, setLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getJobs(analysisId, { page, size: rowsPerPage }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setJobs(r.data)
      setTotal(r.total)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId, page, rowsPerPage])

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell>{t('logAnalyzer.jobs.name')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.trigger')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.start')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.end')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.duration')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.result')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {jobs.map((job, i) => (
              <TableRow key={i} hover>
                <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{job.jobName}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.triggerName ?? '-'}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.thread}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.startTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.endTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Chip size="small" label={formatDuration(job.durationMs)} /></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem" color={job.result && job.result !== 'null' ? 'warning.main' : 'text.secondary'}>{job.result}</Typography></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        showFirstButton showLastButton />
    </Box>
  )
}

// ---- Failures Tab ----

function FailuresTab({ analysisId, isDark, headerTheme }: { analysisId: string; isDark: boolean; headerTheme: { theadBg: string; theadColor: string } }) {
  const { t } = useTranslation()
  const [allFailures, setAllFailures] = useState<RepeatedFailure[]>([])
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const [detailPage, setDetailPage] = useState(0)
  const [filterReason, setFilterReason] = useState('')
  const [loading, setLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getFailures(analysisId, { page: 0, size: 500 }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setAllFailures(r.data)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId])

  const reasons = useMemo(() => {
    const set = new Set(allFailures.map(f => f.reason ?? '-'))
    return Array.from(set).sort()
  }, [allFailures])

  const reasonSummary = useMemo(() => {
    const map = new Map<string, { count: number; totalOccurrences: number }>()
    for (const f of allFailures) {
      const key = f.reason ?? '-'
      const entry = map.get(key) ?? { count: 0, totalOccurrences: 0 }
      entry.count++
      entry.totalOccurrences += f.occurrences
      map.set(key, entry)
    }
    return map
  }, [allFailures])

  const filtered = useMemo(() => {
    if (!filterReason) return allFailures
    return allFailures.filter(f => (f.reason ?? '-') === filterReason)
  }, [allFailures, filterReason])

  const totalFiltered = filtered.length
  const paged = filtered.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)

  const totalOccurrences = useMemo(() => allFailures.reduce((sum, f) => sum + f.occurrences, 0), [allFailures])

  const DETAIL_PAGE_SIZE = 20

  const countColor = (count: number): 'default' | 'warning' | 'error' => {
    if (count >= 500) return 'error'
    if (count >= 100) return 'warning'
    return 'default'
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      {/* Summary */}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.failures.uniqueEntities')}</Typography>
          <Typography variant="h6" fontWeight={700}>{allFailures.length}</Typography>
        </Paper>
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.failures.totalOccurrences')}</Typography>
          <Typography variant="h6" fontWeight={700} color="error.main">{totalOccurrences.toLocaleString()}</Typography>
        </Paper>
        {Array.from(reasonSummary.entries()).map(([reason, data]) => (
          <Chip
            key={reason}
            label={`${reason}: ${data.count} entities / ${data.totalOccurrences.toLocaleString()} total`}
            size="small"
            color={filterReason === reason ? 'primary' : 'default'}
            variant={filterReason === reason ? 'filled' : 'outlined'}
            onClick={() => { setFilterReason(filterReason === reason ? '' : reason); setPage(0) }}
            sx={{ cursor: 'pointer' }}
          />
        ))}
      </Stack>

      {/* Filter */}
      <Stack direction="row" spacing={2} mb={2} alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={reasons}
          value={filterReason || null}
          onChange={(_, v) => { setFilterReason(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.failures.reason')} />}
        />
      </Stack>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell>{t('logAnalyzer.failures.entity')}</TableCell>
              <TableCell>{t('logAnalyzer.failures.reason')}</TableCell>
              <TableCell>{t('logAnalyzer.failures.count')}</TableCell>
              <TableCell>{t('logAnalyzer.failures.firstSeen')}</TableCell>
              <TableCell>{t('logAnalyzer.failures.lastSeen')}</TableCell>
              <TableCell>{t('logAnalyzer.failures.duration')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {paged.map((f, i) => {
              const globalIdx = page * rowsPerPage + i
              const isExpanded = expandedIdx === globalIdx
              const firstTs = f.firstSeen ? new Date(f.firstSeen).getTime() : 0
              const lastTs = f.lastSeen ? new Date(f.lastSeen).getTime() : 0
              const spanMs = lastTs - firstTs
              const detailSlice = isExpanded
                ? f.details.slice(detailPage * DETAIL_PAGE_SIZE, (detailPage + 1) * DETAIL_PAGE_SIZE)
                : []
              const detailTotalPages = Math.ceil(f.details.length / DETAIL_PAGE_SIZE)

              return (
                <Fragment key={globalIdx}>
                  <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => {
                    setExpandedIdx(isExpanded ? null : globalIdx)
                    setDetailPage(0)
                  }}>
                    <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{f.entityId}</Typography></TableCell>
                    <TableCell><Chip size="small" label={f.reason ?? '-'} color="warning" /></TableCell>
                    <TableCell><Chip size="small" label={f.occurrences.toLocaleString()} color={countColor(f.occurrences)} /></TableCell>
                    <TableCell><Typography variant="body2" fontSize="0.8rem">{f.firstSeen?.replace('T', ' ')}</Typography></TableCell>
                    <TableCell><Typography variant="body2" fontSize="0.8rem">{f.lastSeen?.replace('T', ' ')}</Typography></TableCell>
                    <TableCell><Typography variant="body2" fontSize="0.8rem" color="text.secondary">{spanMs > 0 ? formatDuration(spanMs) : '-'}</Typography></TableCell>
                  </TableRow>
                  {isExpanded && (
                    <TableRow>
                      <TableCell colSpan={6} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
                        <Box sx={{ maxHeight: 300, overflowY: 'auto' }}>
                          {detailSlice.map((d, di) => (
                            <Typography key={di} variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" sx={{ py: 0.25 }}>
                              [{d.timestamp?.replace('T', ' ')}] L{d.lineNumber} — {d.message}
                            </Typography>
                          ))}
                        </Box>
                        {detailTotalPages > 1 && (
                          <Stack direction="row" spacing={1} alignItems="center" mt={1}>
                            <Button size="small" disabled={detailPage === 0} onClick={(e) => { e.stopPropagation(); setDetailPage(p => p - 1) }}>
                              {t('logAnalyzer.failures.prev')}
                            </Button>
                            <Typography variant="caption" color="text.secondary">
                              {detailPage * DETAIL_PAGE_SIZE + 1}–{Math.min((detailPage + 1) * DETAIL_PAGE_SIZE, f.details.length)} / {f.details.length.toLocaleString()}
                            </Typography>
                            <Button size="small" disabled={detailPage >= detailTotalPages - 1} onClick={(e) => { e.stopPropagation(); setDetailPage(p => p + 1) }}>
                              {t('logAnalyzer.failures.next')}
                            </Button>
                          </Stack>
                        )}
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              )
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination component="div" count={totalFiltered} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        showFirstButton showLastButton />
    </Box>
  )
}
