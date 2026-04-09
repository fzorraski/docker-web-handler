import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Button, Chip, Paper, Stack, Table, TableHead, TableRow, TableCell, TableBody,
  TableContainer, TableSortLabel, TextField, Typography,
  alpha, useTheme,
} from '@mui/material'
import { CloudUpload, Download } from '@mui/icons-material'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, Legend, ResponsiveContainer } from 'recharts'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { formatDuration } from '../utils/formatDuration'
import fetchWithAuth from '../services/fetchWithAuth'
import type { AnalysisSummary, EndpointStats, StatsExport } from '../services/logAnalyzerService'
import * as logService from '../services/logAnalyzerService'

interface ComparisonRow {
  endpoint: string
  a: EndpointStats | null
  b: EndpointStats | null
  deltaAvg: number
  deltaPct: number
  deltaP95: number
}

export default function StatsComparisonPage() {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([])
  const [exportA, setExportA] = useState<StatsExport | null>(null)
  const [exportB, setExportB] = useState<StatsExport | null>(null)
  const [sort, setSort] = useState<string>('deltaPct')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [showCharts, setShowCharts] = useState(false)
  const [hideNew, setHideNew] = useState(true)
  const [hideRemoved, setHideRemoved] = useState(true)
  const [hideFaster, setHideFaster] = useState(false)
  const [hideSlower, setHideSlower] = useState(false)
  const [hideSimilar, setHideSimilar] = useState(false)
  const [filterEndpoint, setFilterEndpoint] = useState('')

  const handleSort = (field: string, defaultDir: 'asc' | 'desc' = 'asc') => {
    if (sort === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSort(field); setSortDir(defaultDir) }
  }

  useEffect(() => {
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  const loadFromAnalysis = useCallback(async (target: 'A' | 'B', id: string) => {
    try {
      const res = await fetchWithAuth(logService.getStatsExportUrl(id))
      const data = await res.json() as StatsExport
      if (target === 'A') setExportA(data)
      else setExportB(data)
    } catch { /* failed */ }
  }, [])

  const loadFile = useCallback((target: 'A' | 'B') => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.json'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      try {
        const text = await file.text()
        const data = JSON.parse(text) as StatsExport
        if (!data.endpoints || !Array.isArray(data.endpoints)) throw new Error('Invalid format')
        if (target === 'A') setExportA(data)
        else setExportB(data)
      } catch {
        // invalid file
      }
    }
    input.click()
  }, [])

  const rows = useMemo<ComparisonRow[]>(() => {
    if (!exportA || !exportB) return []
    const mapA = new Map(exportA.endpoints.map(s => [s.endpoint, s]))
    const mapB = new Map(exportB.endpoints.map(s => [s.endpoint, s]))
    const all = new Set([...mapA.keys(), ...mapB.keys()])
    const result: ComparisonRow[] = []
    for (const ep of all) {
      const a = mapA.get(ep) ?? null
      const b = mapB.get(ep) ?? null
      const deltaAvg = a && b ? b.avgDurationMs - a.avgDurationMs : 0
      const deltaPct = a && b && a.avgDurationMs > 0 ? (deltaAvg / a.avgDurationMs) * 100 : 0
      const deltaP95 = a && b ? b.p95DurationMs - a.p95DurationMs : 0
      result.push({ endpoint: ep, a, b, deltaAvg, deltaPct, deltaP95 })
    }
    return result
  }, [exportA, exportB])

  const sortedRows = useMemo(() => {
    const cmp = (r: ComparisonRow): number | string => {
      switch (sort) {
        case 'endpoint': return r.endpoint
        case 'aCalls': return r.a?.callCount ?? 0
        case 'bCalls': return r.b?.callCount ?? 0
        case 'aAvg': return r.a?.avgDurationMs ?? 0
        case 'bAvg': return r.b?.avgDurationMs ?? 0
        case 'deltaAvg': return r.deltaAvg
        case 'deltaPct': return r.deltaPct
        case 'aP95': return r.a?.p95DurationMs ?? 0
        case 'bP95': return r.b?.p95DurationMs ?? 0
        case 'deltaP95': return r.deltaP95
        default: return r.deltaPct
      }
    }
    return [...rows].sort((a, b) => {
      const va = cmp(a), vb = cmp(b)
      const dir = sortDir === 'asc' ? 1 : -1
      if (typeof va === 'string') return va.localeCompare(vb as string) * dir
      return ((va as number) - (vb as number)) * dir
    })
  }, [rows, sort, sortDir])

  const endpointOptions = useMemo(() => rows.map(r => r.endpoint).sort(), [rows])

  const filteredRows = useMemo(() => {
    return sortedRows.filter(r => {
      if (filterEndpoint && r.endpoint !== filterEndpoint) return false
      if (!r.a && hideNew) return false
      if (!r.b && hideRemoved) return false
      if (r.a && r.b) {
        if (hideFaster && r.deltaPct < -5) return false
        if (hideSlower && r.deltaPct > 5) return false
        if (hideSimilar && r.deltaPct >= -5 && r.deltaPct <= 5) return false
      }
      return true
    })
  }, [sortedRows, filterEndpoint, hideFaster, hideSlower, hideNew, hideRemoved, hideSimilar])

  const summary = useMemo(() => {
    let faster = 0, slower = 0, similar = 0, newCount = 0, removedCount = 0
    for (const r of rows) {
      if (!r.a) { newCount++; continue }
      if (!r.b) { removedCount++; continue }
      if (r.deltaPct < -5) faster++
      else if (r.deltaPct > 5) slower++
      else similar++
    }
    return { faster, slower, similar, newCount, removedCount, total: rows.length }
  }, [rows])

  const insights = useMemo(() => {
    if (!exportA || !exportB) return null
    const a = exportA.endpoints, b = exportB.endpoints
    const totalCallsA = a.reduce((s, e) => s + e.callCount, 0)
    const totalCallsB = b.reduce((s, e) => s + e.callCount, 0)
    const weightedAvgA = totalCallsA > 0 ? a.reduce((s, e) => s + e.avgDurationMs * e.callCount, 0) / totalCallsA : 0
    const weightedAvgB = totalCallsB > 0 ? b.reduce((s, e) => s + e.avgDurationMs * e.callCount, 0) / totalCallsB : 0
    const totalSlowA = a.reduce((s, e) => s + e.slowCount, 0)
    const totalSlowB = b.reduce((s, e) => s + e.slowCount, 0)
    const maxAvgA = a.reduce((m, e) => e.avgDurationMs > m.avgDurationMs ? e : m, a[0])
    const maxAvgB = b.reduce((m, e) => e.avgDurationMs > m.avgDurationMs ? e : m, b[0])
    const maxP95A = a.reduce((m, e) => e.p95DurationMs > m.p95DurationMs ? e : m, a[0])
    const maxP95B = b.reduce((m, e) => e.p95DurationMs > m.p95DurationMs ? e : m, b[0])
    return {
      totalCallsA, totalCallsB,
      weightedAvgA, weightedAvgB,
      totalSlowA, totalSlowB,
      endpointsA: a.length, endpointsB: b.length,
      maxAvgA, maxAvgB, maxP95A, maxP95B,
    }
  }, [exportA, exportB])

  const chartData = useMemo(() => {
    if (!rows.length) return []
    // Top 15 endpoints by highest avg between A and B, only where both exist
    return rows
      .filter(r => r.a && r.b)
      .sort((a, b) => Math.max(b.a!.avgDurationMs, b.b!.avgDurationMs) - Math.max(a.a!.avgDurationMs, a.b!.avgDurationMs))
      .slice(0, 15)
      .map(r => ({
        endpoint: r.endpoint.length > 30 ? r.endpoint.substring(0, 28) + '...' : r.endpoint,
        fullEndpoint: r.endpoint,
        'A Avg (ms)': Math.round(r.a!.avgDurationMs),
        'B Avg (ms)': Math.round(r.b!.avgDurationMs),
        'A P95 (ms)': r.a!.p95DurationMs,
        'B P95 (ms)': r.b!.p95DurationMs,
      }))
  }, [rows])

  const handleDownloadReport = useCallback(async () => {
    if (!exportA || !exportB) return
    try {
      const visibleEndpoints = new Set(filteredRows.map(r => r.endpoint))
      const filteredA = exportA.endpoints.filter(e => visibleEndpoints.has(e.endpoint))
      const filteredB = exportB.endpoints.filter(e => visibleEndpoints.has(e.endpoint))
      const blob = await logService.compareStats(exportA.label, exportB.label, filteredA, filteredB)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'stats-comparison.html'
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      // failed
    }
  }, [exportA, exportB, filteredRows])

  const labelA = exportA?.label ?? 'A'
  const labelB = exportB?.label ?? 'B'

  const verdict = (r: ComparisonRow) => {
    if (!r.a) return <Chip size="small" label="New in B" color="info" />
    if (!r.b) return <Chip size="small" label="Removed in B" color="warning" />
    if (r.deltaPct < -5) return <Chip size="small" label={`B ${t('logAnalyzer.compare.faster')}`} color="success" />
    if (r.deltaPct > 5) return <Chip size="small" label={`B ${t('logAnalyzer.compare.slower')}`} color="error" />
    return <Chip size="small" label={t('logAnalyzer.compare.similar')} variant="outlined" />
  }

  const deltaColor = (val: number) => val < -5 ? 'success.main' : val > 5 ? 'error.main' : 'text.secondary'

  const hasData = exportA && exportB && rows.length > 0

  return (
    <Box sx={{ maxWidth: 1600, mx: 'auto', p: 3 }}>
      <Typography variant="h4" fontWeight={700} mb={3}>
        {t('logAnalyzer.compare.title')}
      </Typography>

      {/* Source selection */}
      <Stack direction="row" spacing={3} mb={3}>
        {(['A', 'B'] as const).map((side) => {
          const data = side === 'A' ? exportA : exportB
          return (
            <Box key={side} sx={{
              flex: 1, p: 2.5, borderRadius: 2,
              border: '1px solid', borderColor: data ? 'success.main' : 'divider',
              bgcolor: data ? alpha(theme.palette.success.main, isDark ? 0.06 : 0.03) : 'transparent',
            }}>
              <Typography variant="caption" fontWeight={700} color="text.secondary" sx={{ textTransform: 'uppercase', fontSize: '0.65rem', letterSpacing: '0.05em' }}>
                {side === 'A' ? t('logAnalyzer.compare.sourceA') : t('logAnalyzer.compare.sourceB')}
              </Typography>
              {data ? (
                <Stack direction="row" alignItems="center" justifyContent="space-between" mt={1}>
                  <Box>
                    <Typography variant="body1" fontWeight={600}>{data.label}</Typography>
                    <Typography variant="caption" color="text.secondary">{data.endpoints.length} endpoints</Typography>
                  </Box>
                  <Button size="small" onClick={() => { if (side === 'A') setExportA(null); else setExportB(null) }}>
                    {t('logAnalyzer.compare.changeFile')}
                  </Button>
                </Stack>
              ) : (
                <Stack spacing={1.5} mt={1}>
                  <Button size="small" variant="outlined" startIcon={<CloudUpload />} onClick={() => loadFile(side)}>
                    {t('logAnalyzer.compare.importFile')}
                  </Button>
                  {analyses.length > 0 && (
                    <>
                      <Typography variant="caption" color="text.disabled" fontSize="0.65rem">
                        {t('logAnalyzer.compare.orFromAnalysis')}
                      </Typography>
                      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        {analyses.map(a => (
                          <Chip key={a.id} size="small" variant="outlined"
                            label={a.label ?? a.sourceFiles[0]?.filename ?? a.id}
                            onClick={() => loadFromAnalysis(side, a.id)}
                            sx={{ cursor: 'pointer', fontSize: '0.7rem' }}
                          />
                        ))}
                      </Stack>
                    </>
                  )}
                </Stack>
              )}
            </Box>
          )
        })}
      </Stack>

      {/* Insights */}
      {hasData && insights && (() => {
        const pctDiff = (a: number, b: number) => {
          if (a === 0 && b === 0) return null
          if (a === 0) return null
          const pct = Math.abs(((b - a) / a) * 100)
          const who = b > a ? 'B' : 'A'
          return { pct, who }
        }
        const callsDiff = pctDiff(insights.totalCallsA, insights.totalCallsB)
        const avgDiff = pctDiff(insights.weightedAvgA, insights.weightedAvgB)
        const slowDiff = pctDiff(insights.totalSlowA, insights.totalSlowB)

        return (
        <Stack direction="row" spacing={2} mb={3} flexWrap="wrap" useFlexGap>
          <InsightCard label={t('logAnalyzer.compare.totalCalls')}
            valueA={insights.totalCallsA.toLocaleString()} valueB={insights.totalCallsB.toLocaleString()}
            winner={insights.totalCallsB > insights.totalCallsA ? 'B' : insights.totalCallsA > insights.totalCallsB ? 'A' : null}
            detail={callsDiff ? `${callsDiff.who} has ${callsDiff.pct.toFixed(1)}% more calls` : undefined}
            detailColor="text.secondary" />
          <InsightCard label={t('logAnalyzer.compare.weightedAvg')}
            valueA={formatDuration(Math.round(insights.weightedAvgA))} valueB={formatDuration(Math.round(insights.weightedAvgB))}
            winner={insights.weightedAvgB < insights.weightedAvgA ? 'B' : insights.weightedAvgA < insights.weightedAvgB ? 'A' : null}
            detail={avgDiff ? `${avgDiff.who === 'B' ? 'A' : 'B'} is ${avgDiff.pct.toFixed(1)}% faster` : undefined}
            detailColor="success.main" />
          <InsightCard label={t('logAnalyzer.compare.totalSlow')}
            valueA={insights.totalSlowA.toLocaleString()} valueB={insights.totalSlowB.toLocaleString()}
            winner={insights.totalSlowB < insights.totalSlowA ? 'B' : insights.totalSlowA < insights.totalSlowB ? 'A' : null}
            detail={slowDiff ? `${slowDiff.who} has ${slowDiff.pct.toFixed(1)}% more slow calls` : undefined}
            detailColor="error.main" />
          <InsightCard label={t('logAnalyzer.compare.endpoints')}
            valueA={String(insights.endpointsA)} valueB={String(insights.endpointsB)}
            winner={null} />
          <Paper elevation={0} sx={{ px: 2, py: 1.5, border: '1px solid', borderColor: 'divider', flex: '2 1 0', minWidth: 300 }}>
            <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', fontSize: '0.6rem', letterSpacing: '0.05em' }}>
              {t('logAnalyzer.compare.slowestEndpoint')}
            </Typography>
            <Stack spacing={0.5} mt={0.5}>
              {[{ side: 'A', ep: insights.maxAvgA }, { side: 'B', ep: insights.maxAvgB }].map(({ side, ep }) => (
                <Stack key={side} direction="row" spacing={1} alignItems="baseline">
                  <Typography variant="caption" color="text.disabled" fontSize="0.6rem" sx={{ minWidth: 10 }}>{side}</Typography>
                  <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" fontWeight={600} noWrap sx={{ flex: 1 }}>
                    {ep?.endpoint ?? '-'}
                  </Typography>
                  <Typography variant="body2" color="error.main" fontWeight={700} fontSize="0.8rem">
                    {ep ? formatDuration(Math.round(ep.avgDurationMs)) : '-'}
                  </Typography>
                </Stack>
              ))}
            </Stack>
          </Paper>
        </Stack>
        )
      })()}

      {/* Charts */}
      {hasData && chartData.length > 0 && (
        <>
        <Button size="small" onClick={() => setShowCharts(!showCharts)} sx={{ mb: 1, textTransform: 'none', color: 'text.secondary' }}>
          {showCharts ? t('logAnalyzer.compare.hideCharts') : t('logAnalyzer.compare.showCharts')}
        </Button>
        {showCharts && (
        <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2} mb={3}>
          <Paper elevation={0} sx={{ flex: 1, p: 2, border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="subtitle2" fontWeight={600} mb={1}>{t('logAnalyzer.compare.avgDurationChart')}</Typography>
            <ResponsiveContainer width="100%" height={350}>
              <BarChart data={chartData} layout="vertical" margin={{ left: 10, right: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#333' : '#eee'} />
                <XAxis type="number" tick={{ fontSize: 11, fill: isDark ? '#999' : '#666' }} tickFormatter={(v) => `${v}ms`} />
                <YAxis type="category" dataKey="endpoint" tick={{ fontSize: 10, fill: isDark ? '#999' : '#666' }} width={180} />
                <RechartsTooltip contentStyle={{ backgroundColor: isDark ? '#1e1e2e' : '#fff', border: 'none', fontSize: 12 }} />
                <Legend />
                <Bar dataKey="A Avg (ms)" fill="#FF6D00" radius={[0, 3, 3, 0]} />
                <Bar dataKey="B Avg (ms)" fill="#00BCD4" radius={[0, 3, 3, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </Paper>
          <Paper elevation={0} sx={{ flex: 1, p: 2, border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="subtitle2" fontWeight={600} mb={1}>{t('logAnalyzer.compare.p95DurationChart')}</Typography>
            <ResponsiveContainer width="100%" height={350}>
              <BarChart data={chartData} layout="vertical" margin={{ left: 10, right: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#333' : '#eee'} />
                <XAxis type="number" tick={{ fontSize: 11, fill: isDark ? '#999' : '#666' }} tickFormatter={(v) => `${v}ms`} />
                <YAxis type="category" dataKey="endpoint" tick={{ fontSize: 10, fill: isDark ? '#999' : '#666' }} width={180} />
                <RechartsTooltip contentStyle={{ backgroundColor: isDark ? '#1e1e2e' : '#fff', border: 'none', fontSize: 12 }} />
                <Legend />
                <Bar dataKey="A P95 (ms)" fill="#FF6D00" radius={[0, 3, 3, 0]} />
                <Bar dataKey="B P95 (ms)" fill="#00BCD4" radius={[0, 3, 3, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </Paper>
        </Stack>
        )}
        </>
      )}

      {/* Filters + summary */}
      {hasData && (
        <Stack direction="row" spacing={2} mb={2} alignItems="center" flexWrap="wrap" useFlexGap>
          <Autocomplete
            size="small"
            sx={{ minWidth: 280 }}
            options={endpointOptions}
            value={filterEndpoint || null}
            onChange={(_, v) => setFilterEndpoint(v ?? '')}
            renderInput={(params) => <TextField {...params} label="Endpoint" />}
          />
          <Chip label={`${summary.total} endpoints`} variant="outlined" />
          <Chip label={`${summary.faster} B ${t('logAnalyzer.compare.faster')}`} color="success" size="small"
            onClick={() => setHideFaster(!hideFaster)} sx={{ cursor: 'pointer', textDecoration: hideFaster ? 'line-through' : 'none' }} />
          <Chip label={`${summary.slower} B ${t('logAnalyzer.compare.slower')}`} color="error" size="small"
            onClick={() => setHideSlower(!hideSlower)} sx={{ cursor: 'pointer', textDecoration: hideSlower ? 'line-through' : 'none' }} />
          <Chip label={`${summary.similar} ${t('logAnalyzer.compare.similar')}`} variant="outlined" size="small"
            onClick={() => setHideSimilar(!hideSimilar)} sx={{ cursor: 'pointer', textDecoration: hideSimilar ? 'line-through' : 'none' }} />
          {summary.newCount > 0 && (
            <Chip label={`${summary.newCount} New`} color="info" size="small"
              onClick={() => setHideNew(!hideNew)} sx={{ cursor: 'pointer', textDecoration: hideNew ? 'line-through' : 'none' }} />
          )}
          {summary.removedCount > 0 && (
            <Chip label={`${summary.removedCount} Removed`} color="warning" size="small"
              onClick={() => setHideRemoved(!hideRemoved)} sx={{ cursor: 'pointer', textDecoration: hideRemoved ? 'line-through' : 'none' }} />
          )}
          <Box sx={{ flex: 1 }} />
          <Button startIcon={<Download />} onClick={handleDownloadReport}>
            {t('logAnalyzer.compare.downloadReport')}
          </Button>
        </Stack>
      )}

      {/* Comparison table */}
      {hasData && (
        <TableContainer>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow sx={{ '& th': { bgcolor: theadBg, color: theadColor } }}>
                <TableCell><TableSortLabel active={sort === 'endpoint'} direction={sort === 'endpoint' ? sortDir : 'asc'} onClick={() => handleSort('endpoint')} sx={theadSortSx}>Endpoint</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'aCalls'} direction={sort === 'aCalls' ? sortDir : 'desc'} onClick={() => handleSort('aCalls', 'desc')} sx={theadSortSx}>A Calls</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'bCalls'} direction={sort === 'bCalls' ? sortDir : 'desc'} onClick={() => handleSort('bCalls', 'desc')} sx={theadSortSx}>B Calls</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'aAvg'} direction={sort === 'aAvg' ? sortDir : 'desc'} onClick={() => handleSort('aAvg', 'desc')} sx={theadSortSx}>A Avg</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'bAvg'} direction={sort === 'bAvg' ? sortDir : 'desc'} onClick={() => handleSort('bAvg', 'desc')} sx={theadSortSx}>B Avg</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'deltaAvg'} direction={sort === 'deltaAvg' ? sortDir : 'asc'} onClick={() => handleSort('deltaAvg')} sx={theadSortSx}>Δ Avg</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'deltaPct'} direction={sort === 'deltaPct' ? sortDir : 'asc'} onClick={() => handleSort('deltaPct')} sx={theadSortSx}>%</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'aP95'} direction={sort === 'aP95' ? sortDir : 'desc'} onClick={() => handleSort('aP95', 'desc')} sx={theadSortSx}>A P95</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'bP95'} direction={sort === 'bP95' ? sortDir : 'desc'} onClick={() => handleSort('bP95', 'desc')} sx={theadSortSx}>B P95</TableSortLabel></TableCell>
                <TableCell align="right"><TableSortLabel active={sort === 'deltaP95'} direction={sort === 'deltaP95' ? sortDir : 'asc'} onClick={() => handleSort('deltaP95')} sx={theadSortSx}>Δ P95</TableSortLabel></TableCell>
                <TableCell>Verdict</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filteredRows.map(r => (
                <TableRow key={r.endpoint} hover>
                  <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.78rem">{r.endpoint}</Typography></TableCell>
                  <TableCell align="right">{r.a?.callCount ?? '-'}</TableCell>
                  <TableCell align="right">{r.b?.callCount ?? '-'}</TableCell>
                  <TableCell align="right">{r.a ? formatDuration(Math.round(r.a.avgDurationMs)) : '-'}</TableCell>
                  <TableCell align="right">{r.b ? formatDuration(Math.round(r.b.avgDurationMs)) : '-'}</TableCell>
                  <TableCell align="right"><Typography variant="body2" color={deltaColor(r.deltaPct)} fontSize="0.8rem">{r.a && r.b ? formatDuration(Math.round(r.deltaAvg)) : ''}</Typography></TableCell>
                  <TableCell align="right"><Typography variant="body2" color={deltaColor(r.deltaPct)} fontWeight={600} fontSize="0.8rem">{r.a && r.b ? `${r.deltaPct > 0 ? '+' : ''}${r.deltaPct.toFixed(1)}%` : ''}</Typography></TableCell>
                  <TableCell align="right">{r.a ? formatDuration(r.a.p95DurationMs) : '-'}</TableCell>
                  <TableCell align="right">{r.b ? formatDuration(r.b.p95DurationMs) : '-'}</TableCell>
                  <TableCell align="right"><Typography variant="body2" color={deltaColor(r.deltaP95)} fontSize="0.8rem">{r.a && r.b ? formatDuration(r.deltaP95) : ''}</Typography></TableCell>
                  <TableCell>{verdict(r)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}

function InsightCard({ label, valueA, valueB, winner, detail, detailColor, mono }: {
  label: string; valueA: string; valueB: string; winner: 'A' | 'B' | null; detail?: string; detailColor?: string; mono?: boolean
}) {
  const winColor = 'success.main'
  const loseColor = 'error.main'
  const colorA = winner === 'A' ? winColor : winner === 'B' ? loseColor : 'text.primary'
  const colorB = winner === 'B' ? winColor : winner === 'A' ? loseColor : 'text.primary'
  const fontProps = mono ? { fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem' } : {}

  return (
    <Paper elevation={0} sx={{ px: 2, py: 1.5, border: '1px solid', borderColor: 'divider', minWidth: 180, flex: '1 1 0' }}>
      <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', fontSize: '0.6rem', letterSpacing: '0.05em' }}>
        {label}
      </Typography>
      <Stack direction="row" spacing={2} mt={0.5}>
        <Box>
          <Typography variant="caption" color="text.disabled" fontSize="0.6rem">A</Typography>
          <Typography variant="body2" fontWeight={700} color={colorA} {...fontProps}>{valueA}</Typography>
        </Box>
        <Box>
          <Typography variant="caption" color="text.disabled" fontSize="0.6rem">B</Typography>
          <Typography variant="body2" fontWeight={700} color={colorB} {...fontProps}>{valueB}</Typography>
        </Box>
      </Stack>
      {detail && (
        <Typography variant="caption" color={detailColor ?? 'text.secondary'} sx={{ mt: 0.5, display: 'block', fontSize: '0.7rem' }}>
          {detail}
        </Typography>
      )}
    </Paper>
  )
}
