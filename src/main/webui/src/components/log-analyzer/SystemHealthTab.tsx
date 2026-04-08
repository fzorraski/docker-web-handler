import { useState, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Button, LinearProgress, Alert, Paper, Stack, TextField, Chip,
  useTheme,
} from '@mui/material'
import { MonitorHeart, Refresh } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  ComposedChart, Line, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts'
import { chartColor } from '../../utils/chartColors'
import type { SystemHealthResponse } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type State = 'idle' | 'loading' | 'loaded' | 'error'

const BUCKET_SIZE_OPTIONS = [
  { value: 300, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.300' as const },
  { value: 900, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.900' as const },
  { value: 1800, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.1800' as const },
  { value: 3600, labelKey: 'logAnalyzer.anomalyDetection.bucketSizeOptions.3600' as const },
]

const METRIC_OPTIONS = [
  { value: 'count', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.count' as const },
  { value: 'p95', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.p95' as const },
  { value: 'max', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.max' as const },
  { value: 'avg', labelKey: 'logAnalyzer.anomalyDetection.metricOptions.avg' as const },
]

export function SystemHealthTab({ analysisId }: { analysisId: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const [state, setState] = useState<State>('idle')
  const [data, setData] = useState<SystemHealthResponse | null>(null)
  const [error, setError] = useState('')
  const [selectedBucketSize, setSelectedBucketSize] = useState(300)
  const [selectedMetric, setSelectedMetric] = useState('count')
  const [hiddenSignals, setHiddenSignals] = useState<Set<string>>(new Set())

  const analyze = useCallback(async () => {
    setState('loading')
    setError('')
    try {
      const result = await logService.getSystemHealth(analysisId, {
        bucketSize: selectedBucketSize,
        metric: selectedMetric,
      })
      setData(result)
      setHiddenSignals(new Set())
      setState('loaded')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('logAnalyzer.systemHealth.error'))
      setState('error')
    }
  }, [analysisId, selectedBucketSize, selectedMetric, t])

  const signalColors = useMemo(() => {
    if (!data) return {}
    const colors: Record<string, string> = {}
    data.signalTypes.forEach((type, idx) => { colors[type] = chartColor(idx) })
    return colors
  }, [data])

  const durationSignals = useMemo(() => new Set(data?.durationSignals ?? []), [data])

  // Compute max per signal type for the legend display
  const signalMaxes = useMemo(() => {
    if (!data) return {}
    const maxes: Record<string, number> = {}
    for (const type of data.signalTypes) {
      let max = 0
      for (const b of data.buckets) {
        const v = b.values[type] ?? 0
        if (v > max) max = v
      }
      maxes[type] = max
    }
    return maxes
  }, [data])

  // Split signals into left axis (dominant = highest max) and right axis (rest)
  const { leftSignal, rightSignals } = useMemo(() => {
    if (!data || data.signalTypes.length === 0) return { leftSignal: '', rightSignals: [] as string[] }
    const sorted = [...data.signalTypes].sort((a, b) => (signalMaxes[b] ?? 0) - (signalMaxes[a] ?? 0))
    return { leftSignal: sorted[0], rightSignals: sorted.slice(1) }
  }, [data, signalMaxes])

  // Raw chart data — no normalization
  const chartData = useMemo(() => {
    if (!data) return []
    return data.buckets.map(b => ({
      time: b.time,
      ...b.values,
    }))
  }, [data])

  const visibleSignals = useMemo(() => {
    if (!data) return []
    return data.signalTypes.filter(s => !hiddenSignals.has(s))
  }, [data, hiddenSignals])

  const toggleSignal = (signal: string) => {
    setHiddenSignals(prev => {
      const next = new Set(prev)
      if (next.has(signal)) next.delete(signal)
      else next.add(signal)
      return next
    })
  }

  const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
  const textColor = isDark ? '#E8ECF1' : '#424242'

  const controls = (compact: boolean) => (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
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
    </Stack>
  )

  if (state === 'idle') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 2 }}>
        <MonitorHeart sx={{ fontSize: 48, color: 'primary.main', opacity: 0.7 }} />
        <Typography variant="h6" color="text.secondary">{t('logAnalyzer.systemHealth.title')}</Typography>
        <Typography variant="body2" color="text.secondary" textAlign="center" maxWidth={500}>
          {t('logAnalyzer.systemHealth.description')}
        </Typography>
        {controls(false)}
        <Button variant="outlined" size="large" startIcon={<MonitorHeart />} onClick={analyze}>
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
        <Alert severity="error" action={<Button size="small" onClick={analyze}><Refresh /></Button>}>
          {error}
        </Alert>
      </Box>
    )
  }

  if (!data || chartData.length === 0) {
    return (
      <Box sx={{ py: 4, textAlign: 'center' }}>
        <Typography color="text.secondary">{t('logAnalyzer.systemHealth.noData')}</Typography>
      </Box>
    )
  }

  return (
    <Box>
      {/* Controls */}
      <Stack direction="row" spacing={2} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
        {controls(true)}
        <Box sx={{ flex: 1 }} />
        <Button size="small" startIcon={<Refresh />} onClick={analyze}>
          {t('logAnalyzer.anomalyDetection.analyze')}
        </Button>
      </Stack>

      {/* Signal type legend with toggle */}
      <Stack direction="row" spacing={0.75} mb={2} flexWrap="wrap" useFlexGap>
        {data.signalTypes.map(type => {
          const color = signalColors[type]
          const hidden = hiddenSignals.has(type)
          const max = signalMaxes[type] ?? 0
          return (
            <Chip
              key={type}
              label={`${type} (max: ${Math.round(max)})`}
              size="small"
              onClick={() => toggleSignal(type)}
              sx={{
                bgcolor: hidden ? 'transparent' : color + '22',
                color: hidden ? 'text.disabled' : color,
                borderColor: hidden ? 'text.disabled' : color,
                textDecoration: hidden ? 'line-through' : 'none',
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: '0.7rem',
                cursor: 'pointer',
              }}
              variant="outlined"
            />
          )
        })}
      </Stack>

      {/* Timeline chart — dual Y axis: left = dominant signal, right = others */}
      <Paper sx={{ p: 2 }}>
        <Typography variant="subtitle2" mb={1}>{t('logAnalyzer.systemHealth.timeline')}</Typography>
        <ResponsiveContainer width="100%" height={400}>
          <ComposedChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 10, fill: textColor }} interval="preserveStartEnd" />
            <YAxis
              yAxisId="left"
              tick={{ fontSize: 11, fill: signalColors[leftSignal] ?? textColor }}
              stroke={signalColors[leftSignal] ?? textColor}
              label={{ value: `${leftSignal} (${selectedMetric !== 'count' && durationSignals.has(leftSignal) ? 'ms' : 'count'})`, angle: -90, position: 'insideLeft', style: { fontSize: 10, fill: signalColors[leftSignal] ?? textColor } }}
            />
            {rightSignals.length > 0 && (
              <YAxis
                yAxisId="right"
                orientation="right"
                tick={{ fontSize: 11, fill: textColor }}
                stroke={textColor}
                label={{ value: 'count', angle: 90, position: 'insideRight', style: { fontSize: 10, fill: textColor } }}
              />
            )}
            <Tooltip
              content={({ active, payload, label }) => {
                if (!active || !payload || payload.length === 0) return null
                return (
                  <Paper sx={{ p: 1.5, fontSize: 12, maxWidth: 300 }}>
                    <Typography variant="body2" fontWeight={600} mb={0.5}>{label}</Typography>
                    {payload
                      .filter(p => p.value != null && Number(p.value) > 0)
                      .sort((a, b) => Number(b.value) - Number(a.value))
                      .map(p => (
                        <Box key={p.dataKey as string} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: p.color, flexShrink: 0 }} />
                          <Typography variant="caption" sx={{ flex: 1 }}>{p.dataKey as string}</Typography>
                          <Typography variant="caption" fontWeight={600}>
                            {Math.round(Number(p.value))}{selectedMetric !== 'count' && durationSignals.has(p.dataKey as string) ? 'ms' : ''}
                          </Typography>
                        </Box>
                      ))}
                  </Paper>
                )
              }}
            />
            {/* Dominant signal as filled area on left axis */}
            {visibleSignals.includes(leftSignal) && (
              <Area
                yAxisId="left"
                type="monotone"
                dataKey={leftSignal}
                stroke={signalColors[leftSignal]}
                fill={signalColors[leftSignal]}
                fillOpacity={0.1}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
            )}
            {/* Other signals as lines on right axis */}
            {visibleSignals.filter(s => s !== leftSignal).map(type => (
              <Line
                key={type}
                yAxisId="right"
                type="monotone"
                dataKey={type}
                stroke={signalColors[type]}
                strokeWidth={1.5}
                dot={false}
                connectNulls
              />
            ))}
          </ComposedChart>
        </ResponsiveContainer>
      </Paper>
    </Box>
  )
}
