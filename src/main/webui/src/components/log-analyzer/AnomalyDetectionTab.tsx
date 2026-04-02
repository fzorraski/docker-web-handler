import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Button, LinearProgress, Alert, Paper, Stack, TextField,
  useTheme,
} from '@mui/material'
import { TroubleshootOutlined, Refresh } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
  ComposedChart, Area,
} from 'recharts'
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
  { value: 2.0, labelKey: 'logAnalyzer.anomalyDetection.thresholdOptions.2' as const },
  { value: 3.0, labelKey: 'logAnalyzer.anomalyDetection.thresholdOptions.3' as const },
  { value: 5.0, labelKey: 'logAnalyzer.anomalyDetection.thresholdOptions.5' as const },
]

const STATUS_COLORS: Record<string, string> = {
  normal: '#1976d2',
  elevated: '#ffc107',
  ANOMALY: '#ff6d00',
}

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
  const [availableSignalTypes, setAvailableSignalTypes] = useState<string[]>([])

  useEffect(() => {
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
      })
      setData(result)
      setState('loaded')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('logAnalyzer.anomalyDetection.error'))
      setState('error')
    }
  }, [analysisId, selectedSignalType, selectedBucketSize, selectedThreshold, t])

  const signalTypeOptions = useMemo(() => {
    const set = new Set(['ERROR_COUNT', ...availableSignalTypes])
    return Array.from(set)
  }, [availableSignalTypes])

  const chartData = useMemo(() => {
    if (!data) return []
    return data.buckets.map(b => ({
      ...b,
      time: b.bucketLabel,
    }))
  }, [data])

  const statusLabel = useCallback((status: string) => {
    const key = STATUS_LABELS[status as keyof typeof STATUS_LABELS]
    return key ? t(key) : status
  }, [t])

  const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
  const textColor = isDark ? '#E8ECF1' : '#424242'
  const tooltipBg = isDark ? '#1A1D27' : '#ffffff'

  if (state === 'idle') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 2 }}>
        <TroubleshootOutlined sx={{ fontSize: 48, color: 'primary.main', opacity: 0.7 }} />
        <Typography variant="h6" color="text.secondary">{t('logAnalyzer.anomalyDetection.title')}</Typography>
        <Typography variant="body2" color="text.secondary" textAlign="center" maxWidth={500}>
          {t('logAnalyzer.anomalyDetection.description')}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center" sx={{ mt: 1 }}>
          <Autocomplete
            size="small"
            sx={{ minWidth: 200 }}
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
            sx={{ minWidth: 200 }}
            disableClearable
            options={signalTypeOptions}
            value={selectedSignalType}
            onChange={(_, v) => setSelectedSignalType(v)}
            renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.signalType')} />}
          />
          <Autocomplete
            size="small"
            sx={{ minWidth: 200 }}
            disableClearable
            options={THRESHOLD_OPTIONS}
            getOptionLabel={(o) => t(o.labelKey)}
            value={THRESHOLD_OPTIONS.find(o => o.value === selectedThreshold)!}
            onChange={(_, v) => setSelectedThreshold(v.value)}
            isOptionEqualToValue={(o, v) => o.value === v.value}
            renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.threshold')} />}
          />
        </Stack>
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
        <Autocomplete
          size="small"
          sx={{ minWidth: 160 }}
          disableClearable
          options={BUCKET_SIZE_OPTIONS}
          getOptionLabel={(o) => t(o.labelKey)}
          value={BUCKET_SIZE_OPTIONS.find(o => o.value === selectedBucketSize)!}
          onChange={(_, v) => { setSelectedBucketSize(v.value); }}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.bucketSize')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 180 }}
          disableClearable
          options={signalTypeOptions}
          value={selectedSignalType}
          onChange={(_, v) => setSelectedSignalType(v)}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.signalType')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 160 }}
          disableClearable
          options={THRESHOLD_OPTIONS}
          getOptionLabel={(o) => t(o.labelKey)}
          value={THRESHOLD_OPTIONS.find(o => o.value === selectedThreshold)!}
          onChange={(_, v) => setSelectedThreshold(v.value)}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.anomalyDetection.threshold')} />}
        />
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
            {data.peakValue}
          </Typography>
          {data.peakBucketLabel && (
            <Typography variant="caption" color="text.secondary">@ {data.peakBucketLabel}</Typography>
          )}
        </Paper>
        <Paper sx={{ p: 2, minWidth: 140, textAlign: 'center' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.anomalyDetection.p95Value')}</Typography>
          <Typography variant="h5" fontWeight={700}>{data.p95Value}</Typography>
        </Paper>
      </Stack>

      {/* Bucket Timeline */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle2" mb={0.5}>{t('logAnalyzer.anomalyDetection.timeline')}</Typography>
        <Typography variant="caption" color="text.secondary" display="block" mb={1}>
          {t('logAnalyzer.anomalyDetection.timelineSubtitle')}
        </Typography>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: textColor }} interval="preserveStartEnd" />
            <YAxis tick={{ fontSize: 11, fill: textColor }} />
            <Tooltip
              content={({ active, payload, label }) => {
                if (!active || !payload || payload.length === 0) return null
                const bucket = payload[0]?.payload
                if (!bucket) return null
                return (
                  <Paper sx={{ p: 1.5, fontSize: 12, maxWidth: 250 }}>
                    <Typography variant="body2" fontWeight={600}>{label}</Typography>
                    <Typography variant="body2">Count: {bucket.count}</Typography>
                    <Typography variant="body2">Baseline: {bucket.baseline?.toFixed(1)}</Typography>
                    <Typography variant="body2">Ratio: {bucket.ratio?.toFixed(2)}x</Typography>
                    <Typography variant="body2">
                      Status: {statusLabel(bucket.status)}
                    </Typography>
                  </Paper>
                )
              }}
            />
            <Bar dataKey="count" name="count">
              {chartData.map((entry, idx) => (
                <Cell key={idx} fill={STATUS_COLORS[entry.status] ?? STATUS_COLORS.normal} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </Paper>

      {/* Detected Anomalies */}
      {data.anomalies.length > 0 ? (
        <Box sx={{ mb: 3 }}>
          <Typography variant="subtitle2" mb={1}>
            {t('logAnalyzer.anomalyDetection.detectedAnomalies')} ({'>='} {data.threshold}x baseline)
          </Typography>
          <Stack spacing={1}>
            {data.anomalies.map((a, idx) => (
              <Paper
                key={idx}
                sx={{
                  p: 1.5,
                  borderLeft: `4px solid ${a.ratio >= 5 ? '#f44336' : '#ff6d00'}`,
                }}
              >
                <Typography variant="body2" fontWeight={600}>
                  {t('logAnalyzer.anomalyDetection.anomalyAt', {
                    time: a.bucketLabel,
                    value: a.observedValue,
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

      {/* Anomaly Overview Chart */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle2" mb={1}>{t('logAnalyzer.anomalyDetection.title')}</Typography>
        <ResponsiveContainer width="100%" height={250}>
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
                  <Paper sx={{ p: 1.5, fontSize: 12 }}>
                    <Typography variant="body2" fontWeight={600}>{label}</Typography>
                    <Typography variant="body2">Count: {bucket.count}</Typography>
                    <Typography variant="body2">
                      Status: {statusLabel(bucket.status)}
                    </Typography>
                  </Paper>
                )
              }}
            />
            <Area
              type="monotone"
              dataKey="count"
              stroke="#1976d2"
              fill="#1976d2"
              fillOpacity={0.15}
            />
            <Bar dataKey={(entry) => entry.status === 'ANOMALY' ? entry.count : 0} fill="#ff6d00" fillOpacity={0.7} name="anomaly" />
          </ComposedChart>
        </ResponsiveContainer>
      </Paper>

      {/* Correlations */}
      {data.correlations.length > 0 && (
        <Box>
          <Typography variant="subtitle2" mb={1}>{t('logAnalyzer.anomalyDetection.correlatedAnomalies')}</Typography>
          <Stack spacing={1}>
            {data.correlations.map((c, idx) => (
              <Paper key={idx} sx={{ p: 2, borderLeft: '4px solid #9c27b0' }}>
                <Typography variant="body2" fontWeight={600}>
                  {t('logAnalyzer.anomalyDetection.correlationWindow', {
                    start: c.windowStart,
                    end: c.windowEnd,
                  })}
                </Typography>
                <Stack direction="row" spacing={2} mt={0.5}>
                  <Typography variant="caption" color="text.secondary">
                    {t('logAnalyzer.anomalyDetection.signalTypes')}: {c.signalTypes.join(', ')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('logAnalyzer.anomalyDetection.correlationScore', {
                      score: c.score.toFixed(1),
                    })}
                  </Typography>
                </Stack>
              </Paper>
            ))}
          </Stack>
        </Box>
      )}
    </Box>
  )
}
