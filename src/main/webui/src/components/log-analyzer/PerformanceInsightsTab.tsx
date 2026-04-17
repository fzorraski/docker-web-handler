import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Button, LinearProgress, Alert, Paper, Stack, TextField,
  Tooltip as MuiTooltip, IconButton, Dialog, DialogTitle, DialogContent, DialogActions,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer, TableSortLabel,
  useTheme,
} from '@mui/material'
import { QueryStats, Refresh, InfoOutlined, Visibility, OpenInNew } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  AreaChart, Area, LineChart, Line, BarChart, Bar, XAxis, YAxis,
  CartesianGrid, Tooltip, ResponsiveContainer, Legend, Brush, ReferenceLine,
} from 'recharts'
import { formatDuration } from '../../utils/formatDuration'
import { CHART_COLORS } from '../../utils/chartColors'
import type { PerformanceInsightsResponse } from '../../services/logAnalyzerService'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import * as logService from '../../services/logAnalyzerService'

type State = 'idle' | 'loading' | 'loaded' | 'error'

const BUCKET_MINUTES: Record<string, number> = { '1m': 1, '5m': 5, '15m': 15, '1h': 60 }

function formatTime(timestamp: string, bucketWidth: string): string {
  if (!timestamp) return ''
  const d = new Date(timestamp)
  if (bucketWidth === '1h') return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatTimeRange(timestamp: string, bucketWidth: string): string {
  if (!timestamp) return ''
  const d = new Date(timestamp)
  const end = new Date(d.getTime() + (BUCKET_MINUTES[bucketWidth] ?? 15) * 60_000)
  const fmt = (dt: Date) => bucketWidth === '1h'
    ? dt.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  return `${fmt(d)} — ${fmt(end)}`
}

export function PerformanceInsightsTab({ analysisId, initialEndpoint, initialTimestamp, onEndpointConsumed, onGoToApiCalls, active }: {
  analysisId: string; initialEndpoint?: string | null; initialTimestamp?: string | null; onEndpointConsumed?: () => void
  onGoToApiCalls?: (timeFrom: string, timeTo: string) => void; active?: boolean
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()
  const [state, setState] = useState<State>('idle')
  const [data, setData] = useState<PerformanceInsightsResponse | null>(null)
  const [error, setError] = useState('')
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [selectedEndpoint, setSelectedEndpoint] = useState('')
  const [selectedBucketIdx, setSelectedBucketIdx] = useState<number | null>(null)
  const hoveredIdxRef = useRef<string | null>(null)
  const dataRef = useRef<PerformanceInsightsResponse | null>(null)
  dataRef.current = data
  const [bucketDialogOpen, setBucketDialogOpen] = useState(false)
  const [dialogSortKey, setDialogSortKey] = useState<'endpoint' | 'count' | 'avgDurationMs' | 'p95DurationMs'>('count')
  const [dialogSortDir, setDialogSortDir] = useState<'asc' | 'desc'>('desc')

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const handleMouseMove = useCallback((state: any) => {
    if (state?.activeLabel != null) {
      hoveredIdxRef.current = state.activeLabel
    }
  }, [])

  const findBucketIdx = useCallback(() => {
    const d = dataRef.current
    const label = hoveredIdxRef.current
    if (!d || label == null) return -1
    const idx = d.timeBuckets.findIndex(b =>
      formatTime(b.timestamp, d.bucketWidth) === label
    )
    return (idx >= 0 && d.timeBuckets[idx].requestCount > 0) ? idx : -1
  }, [])

  const handleChartWrapperClick = useCallback(() => {
    const idx = findBucketIdx()
    if (idx >= 0) setSelectedBucketIdx(idx)
  }, [findBucketIdx])

  const handleChartContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    const idx = findBucketIdx()
    if (idx >= 0) {
      setSelectedBucketIdx(idx)
      setBucketDialogOpen(true)
    }
  }, [findBucketIdx])

  const chartData = useMemo(() => {
    if (!data || data.timeBuckets.length === 0) return []
    return data.timeBuckets.map((b, i) => ({
      ...b,
      time: formatTime(b.timestamp, data.bucketWidth),
      _index: i,
    }))
  }, [data])

  const selectedBucket = data && selectedBucketIdx != null ? data.timeBuckets[selectedBucketIdx] : null
  const selectedBucketLabel = selectedBucket && data ? formatTime(selectedBucket.timestamp, data.bucketWidth) : null

  const sortedBucketEndpoints = useMemo(() => {
    const sorted = [...(selectedBucket?.endpoints ?? [])]
    sorted.sort((a, b) => {
      const av = a[dialogSortKey]
      const bv = b[dialogSortKey]
      if (typeof av === 'string' && typeof bv === 'string') return dialogSortDir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av)
      return dialogSortDir === 'asc' ? (av as number) - (bv as number) : (bv as number) - (av as number)
    })
    return sorted
  }, [selectedBucket, dialogSortKey, dialogSortDir])

  const handleDialogSort = useCallback((key: typeof dialogSortKey) => {
    setDialogSortDir(prev => dialogSortKey === key ? (prev === 'asc' ? 'desc' : 'asc') : 'desc')
    setDialogSortKey(key)
  }, [dialogSortKey])

  useEffect(() => {
    setState('idle')
    setData(null)
    setError('')
    setSelectedEndpoint('')
    setSelectedBucketIdx(null)
    logService.getEndpoints(analysisId).then(setEndpoints).catch(() => {})
  }, [analysisId])

  const generate = useCallback(async (endpoint?: string, autoSelectTimestamp?: string) => {
    setState('loading')
    setError('')
    try {
      const result = await logService.getPerformanceInsights(analysisId, endpoint || undefined)
      setData(result)
      // Auto-select bucket matching the given timestamp
      if (autoSelectTimestamp && result.timeBuckets.length > 0) {
        let idx = -1
        for (let i = 0; i < result.timeBuckets.length; i++) {
          const bStart = result.timeBuckets[i].timestamp
          const bEnd = i + 1 < result.timeBuckets.length
            ? result.timeBuckets[i + 1].timestamp
            : null
          if (autoSelectTimestamp >= bStart && (bEnd == null || autoSelectTimestamp < bEnd)) {
            idx = i
            break
          }
        }
        setSelectedBucketIdx(idx >= 0 && result.timeBuckets[idx].requestCount > 0 ? idx : null)
      } else {
        setSelectedBucketIdx(null)
      }
      setState('loaded')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('logAnalyzer.insights.error'))
      setState('error')
    }
  }, [analysisId, t])

  // Auto-generate when navigated from Endpoint Stats
  useEffect(() => {
    if (initialEndpoint == null) return
    setSelectedEndpoint(initialEndpoint)
    generate(initialEndpoint)
    onEndpointConsumed?.()
  }, [initialEndpoint, generate, onEndpointConsumed])

  // Auto-generate when navigated from API Calls context menu (all endpoints, auto-select bucket)
  useEffect(() => {
    if (initialTimestamp == null) return
    setSelectedEndpoint('')
    generate(undefined, initialTimestamp)
    onEndpointConsumed?.()
  }, [initialTimestamp, generate, onEndpointConsumed])

  const prevActiveRef = useRef(active)
  useEffect(() => {
    if (prevActiveRef.current && !active) {
      setSelectedBucketIdx(null)
    }
    prevActiveRef.current = active
  }, [active])

  const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
  const textColor = isDark ? '#E8ECF1' : '#424242'
  const tooltipBg = isDark ? '#1A1D27' : '#ffffff'

  if (state === 'idle') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 2 }}>
        <QueryStats sx={{ fontSize: 48, color: 'primary.main', opacity: 0.7 }} />
        <Typography variant="h6" color="text.secondary">{t('logAnalyzer.insights.generateButton')}</Typography>
        <Typography variant="body2" color="text.secondary" textAlign="center" maxWidth={500}>
          {t('logAnalyzer.insights.generateDescription')}
        </Typography>
        <Autocomplete
          size="small"
          sx={{ minWidth: 300 }}
          options={['', ...endpoints]}
          getOptionLabel={(o) => o || t('logAnalyzer.insights.allEndpoints')}
          value={selectedEndpoint}
          onChange={(_, v) => setSelectedEndpoint(v ?? '')}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.stats.endpoint')} />}
        />
        <Button variant="outlined" size="large" startIcon={<QueryStats />} onClick={() => generate(selectedEndpoint)}>
          {t('logAnalyzer.insights.generateButton')}
        </Button>
      </Box>
    )
  }

  if (state === 'loading') {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <LinearProgress sx={{ mb: 2 }} />
        <Typography color="text.secondary">{t('logAnalyzer.insights.generating')}</Typography>
      </Box>
    )
  }

  if (state === 'error') {
    return (
      <Box sx={{ py: 4 }}>
        <Alert severity="error" action={<Button size="small" onClick={() => generate(selectedEndpoint)}>{t('logAnalyzer.insights.refresh')}</Button>}>
          {error}
        </Alert>
      </Box>
    )
  }

  if (!data || chartData.length === 0) {
    return (
      <Box sx={{ py: 4, textAlign: 'center' }}>
        <Typography color="text.secondary">{t('logAnalyzer.insights.noData')}</Typography>
      </Box>
    )
  }

  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
        <Autocomplete
          size="small"
          sx={{ minWidth: 280 }}
          options={['', ...endpoints]}
          getOptionLabel={(o) => o || t('logAnalyzer.insights.allEndpoints')}
          value={selectedEndpoint}
          onChange={(_, v) => {
            setSelectedEndpoint(v ?? '')
            generate(v ?? '')
          }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.stats.endpoint')} />}
        />
        <Typography variant="caption" color="text.secondary">
          {t('logAnalyzer.insights.bucketWidth')}: {data.bucketWidth} | {data.totalBuckets} buckets
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Button size="small" startIcon={<Refresh />} onClick={() => generate(selectedEndpoint)}>
          {t('logAnalyzer.insights.refresh')}
        </Button>
      </Stack>

      {/* Chart 1: Request Volume */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" alignItems="center" gap={0.5} mb={1}>
          <Typography variant="subtitle2">{t('logAnalyzer.insights.requestVolume')}</Typography>
          <MuiTooltip title={t('logAnalyzer.insights.requestVolumeHelp')} arrow placement="right">
            <InfoOutlined sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
          </MuiTooltip>
        </Stack>
        <div onDoubleClick={handleChartWrapperClick} onContextMenu={handleChartContextMenu} style={{ cursor: 'pointer' }}>
        <ResponsiveContainer width="100%" height={250}>
          <AreaChart data={chartData} syncId="performance" onMouseMove={handleMouseMove}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} />
            <YAxis tick={{ fontSize: 11, fill: textColor }} />
            <Tooltip contentStyle={{ backgroundColor: tooltipBg, border: 'none', fontSize: 12 }} />
            <Area type="monotone" dataKey="requestCount" stroke="#FF6D00" fill="#FF6D00" fillOpacity={0.3} name={t('logAnalyzer.insights.requests')} />
            {selectedBucketLabel && <ReferenceLine x={selectedBucketLabel} stroke="#FF6D00" strokeDasharray="4 4" strokeWidth={2} />}
            <Brush dataKey="time" height={25} stroke="#FF6D00" fill={isDark ? '#1A1D27' : '#f5f5f5'} travellerWidth={10} />
          </AreaChart>
        </ResponsiveContainer>
        </div>
      </Paper>

      {/* Chart 2: Response Time */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" alignItems="center" gap={0.5} mb={1}>
          <Typography variant="subtitle2">{t('logAnalyzer.insights.responseTime')}</Typography>
          <MuiTooltip title={t('logAnalyzer.insights.responseTimeHelp')} arrow placement="right">
            <InfoOutlined sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
          </MuiTooltip>
        </Stack>
        <div onDoubleClick={handleChartWrapperClick} onContextMenu={handleChartContextMenu} style={{ cursor: 'pointer' }}>
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={chartData} syncId="performance" onMouseMove={handleMouseMove}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} />
            <YAxis tick={{ fontSize: 11, fill: textColor }} tickFormatter={(v) => formatDuration(v)} />
            <Tooltip contentStyle={{ backgroundColor: tooltipBg, border: 'none', fontSize: 12 }}
              formatter={(value) => formatDuration(Math.round(Number(value)))} />
            <Legend />
            <Line type="monotone" dataKey="avgDurationMs" stroke="#FF6D00" strokeWidth={2} dot={false} name={t('logAnalyzer.insights.avgDuration')} />
            <Line type="monotone" dataKey="p95DurationMs" stroke="#00BCD4" strokeWidth={2} dot={false} name={t('logAnalyzer.insights.p95Duration')} />
            <Line type="monotone" dataKey="maxDurationMs" stroke="#FF5252" strokeWidth={1} dot={false} strokeDasharray="5 5" name={t('logAnalyzer.insights.maxDuration')} />
            {selectedBucketLabel && <ReferenceLine x={selectedBucketLabel} stroke="#FF6D00" strokeDasharray="4 4" strokeWidth={2} />}
            <Brush dataKey="time" height={25} stroke="#00BCD4" fill={isDark ? '#1A1D27' : '#f5f5f5'} travellerWidth={10} />
          </LineChart>
        </ResponsiveContainer>
        </div>
      </Paper>

      {/* Chart 3: Concurrent Requests */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" alignItems="center" gap={0.5} mb={1}>
          <Typography variant="subtitle2">{t('logAnalyzer.insights.concurrency')}</Typography>
          <MuiTooltip title={t('logAnalyzer.insights.concurrencyHelp')} arrow placement="right">
            <InfoOutlined sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
          </MuiTooltip>
        </Stack>
        <div onDoubleClick={handleChartWrapperClick} onContextMenu={handleChartContextMenu} style={{ cursor: 'pointer' }}>
        <ResponsiveContainer width="100%" height={200}>
          <AreaChart data={chartData} syncId="performance" onMouseMove={handleMouseMove}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} />
            <YAxis tick={{ fontSize: 11, fill: textColor }} />
            <Tooltip contentStyle={{ backgroundColor: tooltipBg, border: 'none', fontSize: 12 }} />
            <Area type="monotone" dataKey="concurrentPeak" stroke="#9C27B0" fill="#9C27B0" fillOpacity={0.3} name={t('logAnalyzer.insights.concurrentPeak')} />
            {selectedBucketLabel && <ReferenceLine x={selectedBucketLabel} stroke="#FF6D00" strokeDasharray="4 4" strokeWidth={2} />}
            <Brush dataKey="time" height={25} stroke="#9C27B0" fill={isDark ? '#1A1D27' : '#f5f5f5'} travellerWidth={10} />
          </AreaChart>
        </ResponsiveContainer>
        </div>
      </Paper>

      {/* Chart 4: Top Endpoints by Impact */}
      <Paper sx={{ p: 2 }}>
        <Stack direction="row" alignItems="center" gap={0.5} mb={1}>
          <Typography variant="subtitle2">{t('logAnalyzer.insights.topEndpoints')}</Typography>
          <MuiTooltip title={t('logAnalyzer.insights.topEndpointsHelp')} arrow placement="right">
            <InfoOutlined sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
          </MuiTooltip>
        </Stack>
        <ResponsiveContainer width="100%" height={Math.max(200, data.topEndpointsByImpact.length * 40)}>
          <BarChart data={data.topEndpointsByImpact} layout="vertical" margin={{ left: 150 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis type="number" tick={{ fontSize: 11, fill: textColor }} tickFormatter={(v) => formatDuration(Math.round(v))} />
            <YAxis type="category" dataKey="endpoint" tick={{ fontSize: 11, fill: textColor }} width={140} />
            <Tooltip contentStyle={{ backgroundColor: tooltipBg, border: 'none', fontSize: 12 }}
              formatter={(value) => formatDuration(Math.round(Number(value)))} />
            <Bar dataKey="totalDurationMs" fill="#FF6D00" name={t('logAnalyzer.insights.totalImpact')} radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Paper>

      {/* Selected bucket detail */}
      {selectedBucket && (
        <Paper sx={{ p: 2, mt: 3, borderLeft: '3px solid #FF6D00' }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" mb={1}>
            <Typography variant="subtitle2">
              {formatTimeRange(selectedBucket.timestamp, data.bucketWidth)} — {t('logAnalyzer.insights.bucketDetail')}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button size="small" startIcon={<Visibility />} onClick={() => setBucketDialogOpen(true)}>
                {t('logAnalyzer.insights.viewEndpoints')}
              </Button>
              <Button size="small" onClick={() => setSelectedBucketIdx(null)}>{t('common.close')}</Button>
            </Stack>
          </Stack>
          <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap mb={1}>
            <Typography variant="body2"><strong>{t('logAnalyzer.insights.requests')}:</strong> {selectedBucket.requestCount}</Typography>
            <Typography variant="body2"><strong>{t('logAnalyzer.insights.avgDuration')}:</strong> {formatDuration(Math.round(selectedBucket.avgDurationMs))}</Typography>
            <Typography variant="body2"><strong>{t('logAnalyzer.insights.p95Duration')}:</strong> {formatDuration(selectedBucket.p95DurationMs)}</Typography>
            <Typography variant="body2"><strong>{t('logAnalyzer.insights.maxDuration')}:</strong> {formatDuration(selectedBucket.maxDurationMs)}</Typography>
            <Typography variant="body2"><strong>{t('logAnalyzer.insights.concurrentPeak')}:</strong> {selectedBucket.concurrentPeak}</Typography>
          </Stack>
          {selectedBucket.endpoints.length > 0 && (
            <Box>
              <Typography variant="caption" color="text.secondary" mb={0.5} display="block">{t('logAnalyzer.insights.endpointsInBucket')}</Typography>
              {selectedBucket.endpoints.slice(0, 7).map((ep, i) => (
                <Stack key={ep.endpoint} direction="row" spacing={2} alignItems="center" sx={{ py: 0.25 }}>
                  <Box sx={{ width: 4, height: 16, borderRadius: 2, bgcolor: CHART_COLORS[i % CHART_COLORS.length] }} />
                  <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem" sx={{ flex: 1 }}>
                    {ep.endpoint}
                  </Typography>
                  <Typography variant="body2" fontSize="0.8rem">{ep.count} calls</Typography>
                  <Typography variant="body2" fontSize="0.8rem">{formatDuration(Math.round(ep.avgDurationMs))} avg</Typography>
                </Stack>
              ))}
            </Box>
          )}
        </Paper>
      )}

      {/* Bucket Endpoints Dialog */}
      <Dialog open={bucketDialogOpen} onClose={() => setBucketDialogOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>
          {selectedBucket && `${formatTimeRange(selectedBucket.timestamp, data?.bucketWidth ?? '')} — ${t('logAnalyzer.insights.topEndpointsInBucket')}`}
        </DialogTitle>
        <DialogContent>
          {selectedBucket && (
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap mb={2} sx={{ px: 1, py: 1, bgcolor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)', borderRadius: 1 }}>
              <Typography variant="body2"><strong>{t('logAnalyzer.insights.requests')}:</strong> {selectedBucket.requestCount}</Typography>
              <Typography variant="body2"><strong>{t('logAnalyzer.insights.avgDuration')}:</strong> {formatDuration(Math.round(selectedBucket.avgDurationMs))}</Typography>
              <Typography variant="body2"><strong>{t('logAnalyzer.insights.p95Duration')}:</strong> {formatDuration(selectedBucket.p95DurationMs)}</Typography>
              <Typography variant="body2"><strong>{t('logAnalyzer.insights.maxDuration')}:</strong> {formatDuration(selectedBucket.maxDurationMs)}</Typography>
              <Typography variant="body2"><strong>{t('logAnalyzer.insights.concurrentPeak')}:</strong> {selectedBucket.concurrentPeak}</Typography>
            </Stack>
          )}
          {sortedBucketEndpoints.length === 0 ? (
            <Typography color="text.secondary" textAlign="center" py={2}>{t('logAnalyzer.insights.noData')}</Typography>
          ) : (
            <TableContainer sx={{ maxHeight: 400, overflowY: 'auto' }}>
              <Table size="small" stickyHeader>
                <TableHead>
                  <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
                    <TableCell>#</TableCell>
                    <TableCell sortDirection={dialogSortKey === 'endpoint' ? dialogSortDir : false}>
                      <TableSortLabel active={dialogSortKey === 'endpoint'} direction={dialogSortKey === 'endpoint' ? dialogSortDir : 'asc'} onClick={() => handleDialogSort('endpoint')}>
                        {t('logAnalyzer.stats.endpoint')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right" sortDirection={dialogSortKey === 'count' ? dialogSortDir : false}>
                      <TableSortLabel active={dialogSortKey === 'count'} direction={dialogSortKey === 'count' ? dialogSortDir : 'asc'} onClick={() => handleDialogSort('count')}>
                        {t('logAnalyzer.insights.callCount')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right" sortDirection={dialogSortKey === 'avgDurationMs' ? dialogSortDir : false}>
                      <TableSortLabel active={dialogSortKey === 'avgDurationMs'} direction={dialogSortKey === 'avgDurationMs' ? dialogSortDir : 'asc'} onClick={() => handleDialogSort('avgDurationMs')}>
                        {t('logAnalyzer.insights.avgDuration')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right" sortDirection={dialogSortKey === 'p95DurationMs' ? dialogSortDir : false}>
                      <TableSortLabel active={dialogSortKey === 'p95DurationMs'} direction={dialogSortKey === 'p95DurationMs' ? dialogSortDir : 'asc'} onClick={() => handleDialogSort('p95DurationMs')}>
                        {t('logAnalyzer.insights.p95Duration')}
                      </TableSortLabel>
                    </TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {sortedBucketEndpoints.map((ep, i) => (
                    <TableRow key={ep.endpoint} hover>
                      <TableCell>{i + 1}</TableCell>
                      <TableCell>
                        <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{ep.endpoint}</Typography>
                      </TableCell>
                      <TableCell align="right">{ep.count}</TableCell>
                      <TableCell align="right">{formatDuration(Math.round(ep.avgDurationMs))}</TableCell>
                      <TableCell align="right">{formatDuration(ep.p95DurationMs)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </DialogContent>
        <DialogActions>
          {onGoToApiCalls && selectedBucket && data && (
            <Button
              startIcon={<OpenInNew />}
              onClick={() => {
                const start = new Date(selectedBucket.timestamp)
                const end = new Date(start.getTime() + (BUCKET_MINUTES[data.bucketWidth] ?? 15) * 60_000)
                setBucketDialogOpen(false)
                onGoToApiCalls(start.toISOString(), end.toISOString())
              }}
            >
              {t('logAnalyzer.insights.viewApiCalls')}
            </Button>
          )}
          <Button onClick={() => setBucketDialogOpen(false)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
