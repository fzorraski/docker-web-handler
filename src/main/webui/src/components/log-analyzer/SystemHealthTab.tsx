import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Autocomplete, Alert, Box, Typography, Button, LinearProgress, Paper, Stack, TextField, Chip, Tooltip as MuiTooltip,
  useTheme,
} from '@mui/material'
import { MonitorHeart, Refresh, Science, InfoOutlined } from '@mui/icons-material'
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
  const [pinnedLeftSignal, setPinnedLeftSignal] = useState<string | null>(null)
  const [pinnedRightSignal, setPinnedRightSignal] = useState<string | null>(null)

  useEffect(() => {
    setState('idle')
    setData(null)
    setError('')
    setHiddenSignals(new Set())
    setPinnedLeftSignal(null)
    setPinnedRightSignal(null)
  }, [analysisId])

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
      setPinnedLeftSignal(null)
      setPinnedRightSignal(null)
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

  // Split signals: pinned left/right get their own axis, rest go on left axis
  const { leftSignals, rightSignals, primaryLeft, primaryRight } = useMemo(() => {
    if (!data || data.signalTypes.length === 0) return { leftSignals: [] as string[], rightSignals: [] as string[], primaryLeft: '', primaryRight: '' }
    const visible = data.signalTypes.filter(s => !hiddenSignals.has(s))
    if (visible.length === 0) return { leftSignals: [] as string[], rightSignals: [] as string[], primaryLeft: '', primaryRight: '' }

    const pLeft = pinnedLeftSignal && visible.includes(pinnedLeftSignal) ? pinnedLeftSignal : null
    const pRight = pinnedRightSignal && visible.includes(pinnedRightSignal) && pinnedRightSignal !== pLeft ? pinnedRightSignal : null

    if (pLeft && pRight) {
      // Both pinned: each gets own axis, remaining go on left
      const rest = visible.filter(s => s !== pLeft && s !== pRight)
      return { leftSignals: [pLeft, ...rest], rightSignals: [pRight], primaryLeft: pLeft, primaryRight: pRight }
    }
    if (pRight) {
      // Only right pinned: auto-select left, right gets its own axis
      const autoLeft = [...visible].filter(s => s !== pRight).sort((a, b) => (signalMaxes[b] ?? 0) - (signalMaxes[a] ?? 0))[0] ?? ''
      const rest = visible.filter(s => s !== autoLeft && s !== pRight)
      return { leftSignals: [autoLeft, ...rest], rightSignals: [pRight], primaryLeft: autoLeft, primaryRight: pRight }
    }
    // Only left pinned (or auto): left gets own axis, rest share right
    const left = pLeft ?? [...visible].sort((a, b) => (signalMaxes[b] ?? 0) - (signalMaxes[a] ?? 0))[0]
    return { leftSignals: [left], rightSignals: visible.filter(s => s !== left), primaryLeft: left, primaryRight: '' }
  }, [data, signalMaxes, hiddenSignals, pinnedLeftSignal, pinnedRightSignal])

  const axisLabel = (primary: string, signals: string[]) => {
    if (!primary || signals.length === 0) return ''
    const unit = selectedMetric !== 'count' && durationSignals.has(primary) ? 'ms' : 'count'
    return `${primary} (${unit})`
  }

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

  const leftAxisOptions = useMemo(() => {
    const auto = { value: '', label: t('logAnalyzer.systemHealth.leftAxisAuto') }
    if (!data) return [auto]
    return [auto, ...data.signalTypes.filter(s => s !== pinnedRightSignal).map(s => ({ value: s, label: s }))]
  }, [data, t, pinnedRightSignal])

  const rightAxisOptions = useMemo(() => {
    const none = { value: '', label: t('logAnalyzer.systemHealth.rightAxisNone') }
    if (!data) return [none]
    return [none, ...data.signalTypes.filter(s => s !== pinnedLeftSignal).map(s => ({ value: s, label: s }))]
  }, [data, t, pinnedLeftSignal])

  const controls = (compact: boolean) => (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
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
        <MuiTooltip title={t('logAnalyzer.systemHealth.metricHint')} arrow placement="top">
          <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled', cursor: 'help' }} />
        </MuiTooltip>
      </Box>
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
      {data && data.signalTypes.length > 1 && (<>
        <Autocomplete
          size="small"
          sx={{ minWidth: compact ? 150 : 180 }}
          disableClearable
          options={leftAxisOptions}
          getOptionLabel={(o) => o.label}
          value={leftAxisOptions.find(o => o.value === (pinnedLeftSignal ?? '')) ?? leftAxisOptions[0]}
          onChange={(_, v) => setPinnedLeftSignal(v.value || null)}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.systemHealth.leftAxis')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: compact ? 150 : 180 }}
          disableClearable
          options={rightAxisOptions}
          getOptionLabel={(o) => o.label}
          value={rightAxisOptions.find(o => o.value === (pinnedRightSignal ?? '')) ?? rightAxisOptions[0]}
          onChange={(_, v) => setPinnedRightSignal(v.value || null)}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.systemHealth.rightAxis')} />}
        />
      </>)}
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
        <Alert severity="info" icon={<Science />} variant="outlined" sx={{ maxWidth: 500 }}>
          {t('logAnalyzer.anomalyDetection.experimental')}
        </Alert>
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
              label={`${type} (max: ${Math.round(max)}${durationSignals.has(type) && selectedMetric !== 'count' ? 'ms' : ''})`}
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
        {(() => {
          const leftAxisColor = leftSignals.length === 1 ? (signalColors[primaryLeft] ?? textColor) : textColor
          const rightAxisColor = rightSignals.length === 1 ? (signalColors[primaryRight] ?? textColor) : textColor
          return (
        <ResponsiveContainer width="100%" height={400}>
          <ComposedChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 10, fill: textColor }} interval="preserveStartEnd" />
            <YAxis
              yAxisId="left"
              tick={{ fontSize: 11, fill: leftAxisColor }}
              stroke={leftAxisColor}
              label={{ value: axisLabel(primaryLeft, leftSignals), angle: -90, position: 'insideLeft', style: { fontSize: 10, fill: leftAxisColor } }}
            />
            {rightSignals.length > 0 && (
              <YAxis
                yAxisId="right"
                orientation="right"
                tick={{ fontSize: 11, fill: rightAxisColor }}
                stroke={rightAxisColor}
                label={{ value: axisLabel(primaryRight, rightSignals), angle: 90, position: 'insideRight', style: { fontSize: 10, fill: rightAxisColor } }}
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
            {/* Primary left signal as filled area */}
            {primaryLeft && visibleSignals.includes(primaryLeft) && (
              <Area
                yAxisId="left"
                type="monotone"
                dataKey={primaryLeft}
                stroke={signalColors[primaryLeft]}
                fill={signalColors[primaryLeft]}
                fillOpacity={0.1}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
            )}
            {/* Other left-axis signals as lines */}
            {leftSignals.filter(s => s !== primaryLeft && visibleSignals.includes(s)).map(type => (
              <Line
                key={type}
                yAxisId="left"
                type="monotone"
                dataKey={type}
                stroke={signalColors[type]}
                strokeWidth={1.5}
                dot={false}
                connectNulls
              />
            ))}
            {/* Primary right signal as filled area */}
            {primaryRight && visibleSignals.includes(primaryRight) && (
              <Area
                yAxisId="right"
                type="monotone"
                dataKey={primaryRight}
                stroke={signalColors[primaryRight]}
                fill={signalColors[primaryRight]}
                fillOpacity={0.1}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
            )}
            {/* Other right-axis signals as lines */}
            {rightSignals.filter(s => s !== primaryRight && visibleSignals.includes(s)).map(type => (
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
          )
        })()}
      </Paper>
    </Box>
  )
}
