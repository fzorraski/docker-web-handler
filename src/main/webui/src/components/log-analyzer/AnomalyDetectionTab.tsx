import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Button, LinearProgress, Alert, Paper, Stack, TextField,
  Chip, Collapse, IconButton,
  useTheme,
} from '@mui/material'
import { TroubleshootOutlined, Refresh, ExpandMore, ExpandLess, ArrowForward, Science, Visibility, VisibilityOff } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Brush,
} from 'recharts'
import { chartColor } from '../../utils/chartColors'
import type { AnomalyDetectionResponse } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type State = 'idle' | 'loading' | 'loaded' | 'error'

const BUCKET_SIZE_OPTIONS = [
  { value: 300, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.300' as const },
  { value: 900, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.900' as const },
  { value: 1800, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.1800' as const },
  { value: 3600, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.3600' as const },
]

const THRESHOLD_OPTIONS = [
  { value: 2.0, label: '2.0' },
  { value: 3.0, label: '3.0' },
  { value: 5.0, label: '5.0' },
]

const METHOD_OPTIONS = [
  { value: 'ratio', labelKey: 'logAnalyzer.anomalyDetection.methodOptions.ratio' as const },
  { value: 'zscore', labelKey: 'logAnalyzer.anomalyDetection.methodOptions.zscore' as const },
]

const METRIC_OPTIONS = [
  { value: 'count', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.count' as const },
  { value: 'p95', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.p95' as const },
  { value: 'max', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.max' as const },
  { value: 'avg', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.avg' as const },
]

const STATUS_COLORS: Record<string, string> = {
  normal: '#1976d2',
  elevated: '#ffc107',
  ANOMALY: '#ff6d00',
}

const SEVERITY_COLORS: Record<string, string> = {
  critical: '#f44336',
  high: '#ff6d00',
  medium: '#ffc107',
  low: '#4caf50',
}

const DURATION_SIGNALS = new Set(['API_LATENCY', 'SLOW_QUERY', 'GC_PAUSE', 'POOL_EXHAUSTION', 'JOB_DURATION'])

const STATUS_LABELS = {
  normal: 'logAnalyzer.anomalyDetection.normal' as const,
  elevated: 'logAnalyzer.anomalyDetection.elevated' as const,
  ANOMALY: 'logAnalyzer.anomalyDetection.anomaly' as const,
}

export function AnomalyDetectionTab({ analysisId }: { analysisId: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const [state, setState] = useState<State>('idle')
  const [data, setData] = useState<AnomalyDetectionResponse | null>(null)
  const [error, setError] = useState('')
  const [selectedSignalType, setSelectedSignalType] = useState('ERROR_COUNT')
  const [selectedBucketSize, setSelectedBucketSize] = useState(300)
  const [selectedThreshold, setSelectedThreshold] = useState(3.0)
  const [selectedMetric, setSelectedMetric] = useState('count')
  const [selectedMethod, setSelectedMethod] = useState('ratio')
  const [availableSignalTypes, setAvailableSignalTypes] = useState<string[]>([])

  useEffect(() => {
    setState('idle')
    setData(null)
    setError('')
    logService.getAnomalySignalTypes(analysisId)
      .then(setAvailableSignalTypes)
      .catch(() => {})
  }, [analysisId])

  const analyze = useCallback(async () => {
    setState('loading')
    setError('')
    try {
      const result = await logService.getAnomalyDetection(analysisId, {
        signalType: selectedSignalType,
        bucketSize: selectedBucketSize,
        threshold: selectedThreshold,
        metric: selectedMetric,
        method: selectedMethod,
      })
      setData(result)
      setState('loaded')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('logAnalyzer.anomalyDetection.error'))
      setState('error')
    }
  }, [analysisId, selectedSignalType, selectedBucketSize, selectedThreshold, selectedMetric, selectedMethod, t])

  const signalTypeOptions = useMemo(() => {
    const set = new Set(['ERROR_COUNT', ...availableSignalTypes])
    return Array.from(set)
  }, [availableSignalTypes])

  const chartMetricKey = selectedMetric === 'avg' ? 'avg' : selectedMetric
  const unitSuffix = selectedMetric !== 'count' && DURATION_SIGNALS.has(selectedSignalType) ? 'ms' : ''
  const [expandedCorrelation, setExpandedCorrelation] = useState<number | null>(null)
  const [miniChartVisible, setMiniChartVisible] = useState<number | null>(null)
  const [brushRange, setBrushRange] = useState<{ startIndex: number; endIndex: number } | null>(null)

  const [zoomedBucket, setZoomedBucket] = useState<string | null>(null)

  const zoomToAnomaly = useCallback((bucketLabel: string) => {
    if (!data) return
    if (zoomedBucket === bucketLabel) {
      setBrushRange(null)
      setZoomedBucket(null)
      return
    }
    const idx = data.buckets.findIndex(b => b.bucketLabel === bucketLabel)
    if (idx < 0) return
    const pad = 5
    setBrushRange({ startIndex: Math.max(0, idx - pad), endIndex: Math.min(data.buckets.length - 1, idx + pad) })
    setZoomedBucket(bucketLabel)
  }, [data, zoomedBucket])

  const resetZoom = useCallback(() => { setBrushRange(null); setZoomedBucket(null) }, [])

  const chartData = useMemo(() => {
    if (!data) return []
    return data.buckets.map(b => ({
      ...b,
      time: b.bucketLabel,
      avg: b.count > 0 ? Math.round(b.sum / b.count) : 0,
    }))
  }, [data])

  // Stable signal colors across all correlations
  const correlationSignalColors = useMemo(() => {
    if (!data || data.correlations.length === 0) return {} as Record<string, string>
    const typeSet = new Set<string>()
    for (const c of data.correlations) {
      for (const a of c.anomalies) typeSet.add(a.signalType)
    }
    const colors: Record<string, string> = {}
    Array.from(typeSet).forEach((t, i) => { colors[t] = chartColor(i) })
    return colors
  }, [data])

  // Build correlation chart data — scoped to expanded correlation or all
  const buildCorrelationChart = useCallback((anomalies: { signalType: string; bucketLabel: string; observedValue: number }[]) => {
    const typeSet = new Set<string>()
    const bucketMap = new Map<string, Record<string, number>>()
    for (const a of anomalies) {
      typeSet.add(a.signalType)
      const existing = bucketMap.get(a.bucketLabel) ?? {}
      existing[a.signalType] = Math.max(existing[a.signalType] ?? 0, a.observedValue)
      bucketMap.set(a.bucketLabel, existing)
    }
    const types = Array.from(typeSet)
    const isDuration = (t: string) => selectedMetric !== 'count' && DURATION_SIGNALS.has(t)
    const durationTypes = types.filter(isDuration)
    const countTypes = types.filter(t => !isDuration(t))
    let left: string[], right: string[]
    if (durationTypes.length > 0 && countTypes.length > 0) {
      left = durationTypes; right = countTypes
    } else if (types.length > 1) {
      const maxByType: Record<string, number> = {}
      for (const [, values] of bucketMap) {
        for (const t of types) { maxByType[t] = Math.max(maxByType[t] ?? 0, values[t] ?? 0) }
      }
      const sorted = [...types].sort((a, b) => (maxByType[b] ?? 0) - (maxByType[a] ?? 0))
      left = [sorted[0]]; right = sorted.slice(1)
    } else {
      left = types; right = []
    }
    const bucketOrder = data?.buckets.map(b => b.bucketLabel) ?? []
    const sortedBuckets = Array.from(bucketMap.entries())
      .sort((a, b) => bucketOrder.indexOf(a[0]) - bucketOrder.indexOf(b[0]))
    const rows = sortedBuckets.map(([label, values]) => ({ time: label, ...values }))
    return { rows, left, right, types }
  }, [data, selectedMetric])

  const { correlationChartData, correlationLeftTypes, correlationRightTypes } = useMemo(() => {
    if (!data || data.correlations.length === 0) return { correlationChartData: [], correlationLeftTypes: [] as string[], correlationRightTypes: [] as string[] }
    const source = expandedCorrelation != null ? data.correlations[expandedCorrelation] : null
    const anomalies = source ? source.anomalies : data.correlations.flatMap(c => c.anomalies)
    const { rows, left, right } = buildCorrelationChart(anomalies)
    return { correlationChartData: rows, correlationLeftTypes: left, correlationRightTypes: right }
  }, [data, expandedCorrelation, buildCorrelationChart])

  const statusLabel = (status: string) => {
    const key = STATUS_LABELS[status as keyof typeof STATUS_LABELS]
    return key ? t(key) : status
  }

  const isZScore = data?.method === 'zscore'
  const scoreLabel = isZScore ? 'z-score' : 'ratio'

  const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
  const textColor = isDark ? '#E8ECF1' : '#424242'

  // Shared controls rendered in both idle and loaded states
  const controlsRow = (compact: boolean) => (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
      <Autocomplete
        size="small"
        sx={{ minWidth: compact ? 140 : 180 }}
        disableClearable
        options={METHOD_OPTIONS}
        getOptionLabel={(o) => t(o.labelKey)}
        value={METHOD_OPTIONS.find(o => o.value === selectedMethod)!}
        onChange={(_, v) => setSelectedMethod(v.value)}
        isOptionEqualToValue={(o, v) => o.value === v.value}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.method')} />}
      />
      <Autocomplete
        size="small"
        sx={{ minWidth: compact ? 140 : 180 }}
        disableClearable
        options={signalTypeOptions}
        value={selectedSignalType}
        onChange={(_, v) => setSelectedSignalType(v)}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.signalType')} />}
      />
      <Autocomplete
        size="small"
        sx={{ minWidth: compact ? 130 : 160 }}
        disableClearable
        options={METRIC_OPTIONS}
        getOptionLabel={(o) => t(o.labelKey)}
        value={METRIC_OPTIONS.find(o => o.value === selectedMetric)!}
        onChange={(_, v) => setSelectedMetric(v.value)}
        isOptionEqualToValue={(o, v) => o.value === v.value}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.metric')} />}
      />
      <Autocomplete
        size="small"
        sx={{ minWidth: compact ? 130 : 160 }}
        disableClearable
        options={BUCKET_SIZE_OPTIONS}
        getOptionLabel={(o) => t(o.labelKey)}
        value={BUCKET_SIZE_OPTIONS.find(o => o.value === selectedBucketSize)!}
        onChange={(_, v) => setSelectedBucketSize(v.value)}
        isOptionEqualToValue={(o, v) => o.value === v.value}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.bucketSize')} />}
      />
      <Autocomplete
        size="small"
        sx={{ minWidth: compact ? 100 : 130 }}
        disableClearable
        options={THRESHOLD_OPTIONS}
        getOptionLabel={(o) => o.label}
        value={THRESHOLD_OPTIONS.find(o => o.value === selectedThreshold)!}
        onChange={(_, v) => setSelectedThreshold(v.value)}
        isOptionEqualToValue={(o, v) => o.value === v.value}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.threshold')} />}
      />
    </Stack>
  )

  if (state === 'idle') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 2 }}>
        <TroubleshootOutlined sx={{ fontSize: 48, color: 'primary.main', opacity: 0.7 }} />
        <Typography variant="h6" color="text.secondary">{t('logAnalyzer.anomalyDetection.title')}</Typography>
        <Typography variant="body2" color="text.secondary" textAlign="center" maxWidth={500}>
          {t('logAnalyzer.anomalyDetection.description')}
        </Typography>
        <Alert severity="info" icon={<Science />} variant="outlined" sx={{ maxWidth: 500 }}>
          {t('logAnalyzer.anomalyDetection.experimental')}
        </Alert>
        {controlsRow(false)}
        <Button variant="outlined" size="large" startIcon={<TroubleshootOutlined />} onClick={analyze}>
          {t('logAnalyzer.anomalyDetection.analyze')}
        </Button>
      </Box>
    )
  }

  if (state === 'loading') {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <LinearProgress sx={{ mb: 2 }} />
        <Typography color="text.secondary">{t('logAnalyzer.anomalyDetection.generating')}</Typography>
      </Box>
    )
  }

  if (state === 'error') {
    return (
      <Box sx={{ py: 4 }}>
        <Alert severity="error" action={<Button size="small" onClick={analyze}>{t('logAnalyzer.insights.refresh')}</Button>}>
          {error}
        </Alert>
      </Box>
    )
  }

  if (!data || chartData.length === 0) {
    return (
      <Box sx={{ py: 4, textAlign: 'center' }}>
        <Typography color="text.secondary">{t('logAnalyzer.anomalyDetection.noAnomalies')}</Typography>
      </Box>
    )
  }

  return (
    <Box>
      {/* Controls bar */}
      <Stack direction="row" spacing={2} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
        {controlsRow(true)}
        <Box sx={{ flex: 1 }} />
        <Button size="small" startIcon={<Refresh />} onClick={analyze}>
          {t('logAnalyzer.anomalyDetection.analyze')}
        </Button>
      </Stack>

      {/* Summary cards */}
      <Stack direction="row" spacing={2} mb={3} flexWrap="wrap" useFlexGap>
        <Paper sx={{ p: 2, minWidth: 140, textAlign: 'center' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.totalBuckets')}</Typography>
          <Typography variant="h5" fontWeight={700}>{data.totalBuckets}</Typography>
        </Paper>
        <Paper sx={{ p: 2, minWidth: 140, textAlign: 'center' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.anomalyBuckets')}</Typography>
          <Typography variant="h5" fontWeight={700} color="error.main">{data.anomalyBuckets}</Typography>
        </Paper>
        <Paper sx={{ p: 2, minWidth: 140, textAlign: 'center' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.peakValue')}</Typography>
          <Typography variant="h5" fontWeight={700} sx={{ color: '#ff6d00' }}>
            {data.peakValue}{unitSuffix}
          </Typography>
          {data.peakBucketLabel && (
            <Typography variant="caption" color="text.secondary">@ {data.peakBucketLabel}</Typography>
          )}
        </Paper>
        <Paper sx={{ p: 2, minWidth: 140, textAlign: 'center' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.p95Value')}</Typography>
          <Typography variant="h5" fontWeight={700}>{data.p95Value}{unitSuffix}</Typography>
        </Paper>
      </Stack>

      {/* Bucket Timeline */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" alignItems="center" spacing={1} mb={0.5}>
          <Typography variant="subtitle2">{t('logAnalyzer.anomalyDetection.timeline')}</Typography>
          {brushRange && (
            <Chip size="small" label={`${chartData[brushRange.startIndex]?.time ?? ''} — ${chartData[brushRange.endIndex]?.time ?? ''}`}
              onDelete={resetZoom} sx={{ fontSize: '0.7rem', height: 22 }} />
          )}
        </Stack>
        <Typography variant="caption" color="text.secondary" display="block" mb={0.5}>
          {t('logAnalyzer.anomalyDetection.timelineSubtitle')}
        </Typography>
        <Stack direction="row" spacing={2} mb={1} flexWrap="wrap" useFlexGap alignItems="center">
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: STATUS_COLORS.normal }} />
            <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.normal')}</Typography>
          </Stack>
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: STATUS_COLORS.elevated }} />
            <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.elevated')}</Typography>
          </Stack>
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: STATUS_COLORS.ANOMALY }} />
            <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.anomaly')}</Typography>
          </Stack>
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Box sx={{ width: 16, height: 0, borderTop: '2px dashed #8884d8' }} />
            <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.baseline')}</Typography>
          </Stack>
        </Stack>
        <ResponsiveContainer width="100%" height={300}>
          <ComposedChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} interval="preserveStartEnd" />
            <YAxis tick={{ fontSize: 11, fill: textColor }} />
            <Tooltip
              content={({ active, payload, label }) => {
                if (!active || !payload || payload.length === 0) return null
                const bucket = payload[0]?.payload
                if (!bucket) return null
                return (
                  <Paper sx={{ p: 1.5, fontSize: 12, maxWidth: 280 }}>
                    <Typography variant="body2" fontWeight={600}>{label}</Typography>
                    <Typography variant="body2">Count: {bucket.count}</Typography>
                    {selectedMetric !== 'count' && (
                      <Typography variant="body2">{selectedMetric}: {chartMetricKey === 'avg' ? bucket.avg : bucket[chartMetricKey]}{unitSuffix}</Typography>
                    )}
                    <Typography variant="body2">Baseline: {bucket.baseline?.toFixed(1)}</Typography>
                    <Typography variant="body2">{scoreLabel}: {bucket.ratio?.toFixed(2)}</Typography>
                    <Typography variant="body2">
                      Status: {statusLabel(bucket.status)}
                    </Typography>
                  </Paper>
                )
              }}
            />
            {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
            <Bar dataKey={chartMetricKey} name={selectedMetric}
              shape={(props: any) => (
                <rect x={props.x} y={props.y} width={props.width} height={props.height}
                  fill={STATUS_COLORS[props.payload?.status] ?? STATUS_COLORS.normal} />
              )}
            />
            <Line type="monotone" dataKey="baseline" stroke="#8884d8" strokeWidth={1.5} strokeDasharray="5 3" dot={false} connectNulls />
            <Brush dataKey="time" height={25} stroke={isDark ? '#555' : '#ccc'} fill={isDark ? '#1e1e1e' : '#fafafa'}
              tickFormatter={() => ''} onChange={(range) => setBrushRange(range as { startIndex: number; endIndex: number })}
              startIndex={brushRange?.startIndex ?? 0} endIndex={brushRange?.endIndex ?? chartData.length - 1}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </Paper>

      {/* Detected Anomalies */}
      {data.anomalies.length > 0 ? (
        <Box sx={{ mb: 3 }}>
          <Typography variant="subtitle2" mb={1}>
            {t('logAnalyzer.anomalyDetection.detectedAnomalies')} ({scoreLabel} {'>='} {data.threshold})
          </Typography>
          <Stack spacing={1} sx={{ maxHeight: 340, overflowY: 'auto' }}>
            {data.anomalies.map((a, idx) => (
              <Paper
                key={idx}
                sx={{
                  p: 1.5,
                  borderLeft: `4px solid ${a.ratio >= 5 ? '#f44336' : '#ff6d00'}`,
                  cursor: 'pointer',
                  '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' },
                }}
                onClick={() => zoomToAnomaly(a.bucketLabel)}
              >
                <Typography variant="body2" fontWeight={600}>
                  {t('logAnalyzer.anomalyDetection.anomalyAt', {
                    time: a.bucketLabel,
                    value: Math.round(a.observedValue),
                    ratio: a.ratio.toFixed(1),
                  })}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('logAnalyzer.anomalyDetection.baselineAt', {
                    baseline: a.baselineValue.toFixed(1),
                    bucket: a.count,
                    ratio: a.ratio.toFixed(2),
                  })}
                </Typography>
              </Paper>
            ))}
          </Stack>
        </Box>
      ) : (
        <Box sx={{ mb: 3 }}>
          <Typography color="text.secondary">{t('logAnalyzer.anomalyDetection.noAnomalies')}</Typography>
        </Box>
      )}

      {/* Correlation Chart */}
      {correlationChartData.length > 0 && (
        <Paper sx={{ p: 2, mb: 3 }}>
          <Stack direction="row" alignItems="center" spacing={1} mb={0.5}>
            <Typography variant="subtitle2">{t('logAnalyzer.anomalyDetection.correlatedAnomalies')}</Typography>
            {expandedCorrelation != null && data.correlations[expandedCorrelation] && (
              <Chip size="small" label={`${data.correlations[expandedCorrelation].windowStart} — ${data.correlations[expandedCorrelation].windowEnd}`}
                onDelete={() => setExpandedCorrelation(null)}
                sx={{ fontSize: '0.7rem', height: 22 }} />
            )}
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" mb={0.5}>
            {expandedCorrelation != null
              ? t('logAnalyzer.anomalyDetection.correlationChartFiltered')
              : t('logAnalyzer.anomalyDetection.correlationChartSubtitle')}
          </Typography>
          <Stack direction="row" spacing={2} mb={1} flexWrap="wrap" useFlexGap alignItems="center">
            {[...correlationLeftTypes, ...correlationRightTypes].map(type => (
              <Stack key={type} direction="row" spacing={0.5} alignItems="center">
                <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: correlationSignalColors[type] }} />
                <Typography variant="caption" color="text.secondary">
                  {type} ({correlationRightTypes.includes(type) ? t('logAnalyzer.anomalyDetection.rightAxisLabel') : t('logAnalyzer.anomalyDetection.leftAxisLabel')})
                </Typography>
              </Stack>
            ))}
          </Stack>
          <ResponsiveContainer width="100%" height={220}>
            <ComposedChart data={correlationChartData}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} />
              <YAxis yAxisId="left" tick={{ fontSize: 11, fill: textColor }}
                label={correlationLeftTypes.length === 1
                  ? { value: correlationLeftTypes[0], angle: -90, position: 'insideLeft', style: { fontSize: 10, fill: correlationSignalColors[correlationLeftTypes[0]] ?? textColor } }
                  : undefined}
              />
              {correlationRightTypes.length > 0 && (
                <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11, fill: textColor }}
                  label={correlationRightTypes.length === 1
                    ? { value: correlationRightTypes[0], angle: 90, position: 'insideRight', style: { fontSize: 10, fill: correlationSignalColors[correlationRightTypes[0]] ?? textColor } }
                    : undefined}
                />
              )}
              <Tooltip
                content={({ active, payload, label }) => {
                  if (!active || !payload || payload.length === 0) return null
                  return (
                    <Paper sx={{ p: 1.5, fontSize: 12, maxWidth: 280 }}>
                      <Typography variant="body2" fontWeight={600} mb={0.5}>{label}</Typography>
                      {payload.filter(p => Number(p.value) > 0).map(p => {
                        const sig = p.dataKey as string
                        const suffix = selectedMetric !== 'count' && DURATION_SIGNALS.has(sig) ? 'ms' : ''
                        return (
                          <Box key={sig} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                            <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: p.color, flexShrink: 0 }} />
                            <Typography variant="caption" sx={{ flex: 1 }}>{sig}</Typography>
                            <Typography variant="caption" fontWeight={600}>{Math.round(Number(p.value))}{suffix}</Typography>
                          </Box>
                        )
                      })}
                    </Paper>
                  )
                }}
              />
              {correlationLeftTypes.map(type => (
                <Bar key={type} yAxisId="left" dataKey={type} fill={correlationSignalColors[type]} fillOpacity={0.8} />
              ))}
              {correlationRightTypes.map(type => (
                <Bar key={type} yAxisId="right" dataKey={type} fill={correlationSignalColors[type]} fillOpacity={0.8} />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        </Paper>
      )}

      {/* Correlation Details */}
      {data.correlations.length > 0 && (
        <Box>
          <Typography variant="subtitle2" mb={1}>{t('logAnalyzer.anomalyDetection.correlatedAnomalies')}</Typography>
          <Stack spacing={1}>
            {data.correlations.map((c, idx) => {
              const isExpanded = expandedCorrelation === idx
              const severityColor = SEVERITY_COLORS[c.severity] ?? SEVERITY_COLORS.low
              return (
                <Paper key={idx} sx={{ borderLeft: `4px solid ${severityColor}`, overflow: 'hidden' }}>
                  <Box
                    sx={{ p: 1.5, cursor: 'pointer', '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' } }}
                    onClick={() => setExpandedCorrelation(isExpanded ? null : idx)}
                  >
                    <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap">
                      <Typography variant="body2" fontWeight={600}>
                        {c.windowStart}{c.windowEnd !== c.windowStart ? ` — ${c.windowEnd}` : ''}
                      </Typography>
                      <Chip size="small" label={c.severity} sx={{ bgcolor: severityColor + '22', color: severityColor, fontWeight: 600, fontSize: '0.7rem', height: 22 }} />
                      <Chip size="small" label={`${t('logAnalyzer.anomalyDetection.correlationScore', { score: c.score.toFixed(1) })}`} variant="outlined" sx={{ fontSize: '0.7rem', height: 22 }} />
                      <Box sx={{ flex: 1 }} />
                      <IconButton size="small" sx={{ color: 'text.secondary' }}>
                        {isExpanded ? <ExpandLess sx={{ fontSize: 18 }} /> : <ExpandMore sx={{ fontSize: 18 }} />}
                      </IconButton>
                    </Stack>

                    {/* Causal chain */}
                    {c.causalChain && c.causalChain.length >= 2 && (
                      <Stack direction="row" alignItems="center" spacing={0.5} mt={0.75} flexWrap="wrap">
                        <Typography variant="caption" color="text.secondary" sx={{ mr: 0.5 }}>
                          {t('logAnalyzer.anomalyDetection.likelyCause')}:
                        </Typography>
                        {c.causalChain.map((type, ci) => (
                          <Stack key={type} direction="row" alignItems="center" spacing={0.5}>
                            <Chip size="small" label={type} sx={{
                              fontSize: '0.65rem', height: 20,
                              bgcolor: ci === 0 ? (severityColor + '22') : undefined,
                              color: ci === 0 ? severityColor : undefined,
                              fontWeight: ci === 0 ? 600 : 400,
                            }} />
                            {ci < c.causalChain.length - 1 && (
                              <ArrowForward sx={{ fontSize: 12, color: 'text.disabled' }} />
                            )}
                          </Stack>
                        ))}
                      </Stack>
                    )}

                    {/* Signal type chips (when collapsed) */}
                    {!isExpanded && (!c.causalChain || c.causalChain.length < 2) && (
                      <Stack direction="row" spacing={0.5} mt={0.75} flexWrap="wrap">
                        {c.signalTypes.map(type => {
                          const typeRatio = c.anomalies.filter(a => a.signalType === type)
                            .reduce((max, a) => Math.max(max, a.ratio), 0)
                          return (
                            <Chip key={type} size="small" label={`${type} (${typeRatio.toFixed(1)})`}
                              sx={{ fontSize: '0.65rem', height: 20 }} />
                          )
                        })}
                      </Stack>
                    )}
                  </Box>

                  {/* Expanded detail with mini chart */}
                  <Collapse in={isExpanded}>
                    <Box sx={{ px: 1.5, pb: 1.5, pt: 0 }}>
                      {/* Mini correlation chart toggle + chart */}
                      {(() => {
                        const showChart = miniChartVisible === idx
                        const { rows, left, right, types } = showChart ? buildCorrelationChart(c.anomalies) : { rows: [], left: [], right: [], types: c.signalTypes }
                        if (c.anomalies.length === 0) return null
                        return (
                          <Box sx={{ mb: 2 }}>
                            <Stack direction="row" spacing={0.5} alignItems="center" mb={showChart ? 0.5 : 0}>
                              <IconButton size="small" onClick={(e) => { e.stopPropagation(); setMiniChartVisible(showChart ? null : idx) }}
                                sx={{ color: showChart ? 'primary.main' : 'text.secondary' }}>
                                {showChart ? <VisibilityOff sx={{ fontSize: 16 }} /> : <Visibility sx={{ fontSize: 16 }} />}
                              </IconButton>
                              <Typography variant="caption" color="text.secondary" fontSize="0.7rem">
                                {t('logAnalyzer.anomalyDetection.toggleMiniChart')}
                              </Typography>
                            </Stack>
                            <Collapse in={showChart}>
                            <Stack direction="row" spacing={1.5} mb={0.5} flexWrap="wrap" useFlexGap alignItems="center">
                              {types.map(type => (
                                <Stack key={type} direction="row" spacing={0.5} alignItems="center">
                                  <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: correlationSignalColors[type] }} />
                                  <Typography variant="caption" color="text.secondary" fontSize="0.65rem">{type}</Typography>
                                </Stack>
                              ))}
                            </Stack>
                            <ResponsiveContainer width="100%" height={150}>
                              <ComposedChart data={rows}>
                                <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                                <XAxis dataKey="time" tick={{ fontSize: 10, fill: textColor }} />
                                <YAxis yAxisId="left" tick={{ fontSize: 10, fill: textColor }} />
                                {right.length > 0 && (
                                  <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 10, fill: textColor }} />
                                )}
                                <Tooltip
                                  content={({ active, payload, label: lbl }) => {
                                    if (!active || !payload || payload.length === 0) return null
                                    return (
                                      <Paper sx={{ p: 1, fontSize: 11, maxWidth: 250 }}>
                                        <Typography variant="caption" fontWeight={600}>{lbl}</Typography>
                                        {payload.filter(p => Number(p.value) > 0).map(p => {
                                          const sig = p.dataKey as string
                                          const sfx = selectedMetric !== 'count' && DURATION_SIGNALS.has(sig) ? 'ms' : ''
                                          return (
                                            <Box key={sig} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                              <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: p.color, flexShrink: 0 }} />
                                              <Typography variant="caption" fontSize="0.65rem" sx={{ flex: 1 }}>{sig}</Typography>
                                              <Typography variant="caption" fontSize="0.65rem" fontWeight={600}>{Math.round(Number(p.value))}{sfx}</Typography>
                                            </Box>
                                          )
                                        })}
                                      </Paper>
                                    )
                                  }}
                                />
                                {left.map(type => (
                                  <Bar key={type} yAxisId="left" dataKey={type} fill={correlationSignalColors[type]} fillOpacity={0.8} />
                                ))}
                                {right.map(type => (
                                  <Bar key={type} yAxisId="right" dataKey={type} fill={correlationSignalColors[type]} fillOpacity={0.8} />
                                ))}
                              </ComposedChart>
                            </ResponsiveContainer>
                            </Collapse>
                          </Box>
                        )
                      })()}
                      {c.signalTypes.map(type => {
                        const typeAnomalies = c.anomalies.filter(a => a.signalType === type)
                        return (
                          <Box key={type} sx={{ mb: 1 }}>
                            <Stack direction="row" alignItems="center" spacing={1} mb={0.5}>
                              <Chip size="small" label={type} sx={{ fontSize: '0.7rem', height: 22, bgcolor: (correlationSignalColors[type] ?? '#1976d2') + '22', color: correlationSignalColors[type] ?? '#1976d2' }} />
                              <Typography variant="caption" color="text.secondary">
                                {typeAnomalies.length} anomal{typeAnomalies.length === 1 ? 'y' : 'ies'}
                              </Typography>
                            </Stack>
                            {typeAnomalies.map((a, ai) => (
                              <Box key={ai} sx={{ pl: 2, py: 0.25 }}>
                                <Typography variant="caption" fontFamily="'JetBrains Mono', monospace" fontSize="0.7rem">
                                  {a.bucketLabel} — {Math.round(a.observedValue)}{unitSuffix} (baseline {a.baselineValue.toFixed(1)}{unitSuffix}, {scoreLabel} {a.ratio.toFixed(1)})
                                </Typography>
                              </Box>
                            ))}
                          </Box>
                        )
                      })}
                    </Box>
                  </Collapse>
                </Paper>
              )
            })}
          </Stack>
        </Box>
      )}
    </Box>
  )
}
