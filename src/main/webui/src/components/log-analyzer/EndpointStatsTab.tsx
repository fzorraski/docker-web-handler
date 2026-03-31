import { useState, useEffect, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Chip, LinearProgress, TextField, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  useTheme,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { formatDuration } from '../../utils/formatDuration'
import type { EndpointStats } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function EndpointStatsTab({ analysisId }: { analysisId: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [stats, setStats] = useState<EndpointStats[]>([])
  const [sortField, setSortField] = useState<keyof EndpointStats>('callCount')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading(true)
    logService.getApiStats(analysisId).then(setStats).catch(() => {}).finally(() => setLoading(false))
  }, [analysisId])

  const endpointOptions = useMemo(() => stats.map(s => s.endpoint), [stats])

  const filtered = useMemo(() => {
    if (!filterEndpoint) return stats
    return stats.filter(s => s.endpoint.toLowerCase().includes(filterEndpoint.toLowerCase()))
  }, [stats, filterEndpoint])

  const sorted = useMemo(() => {
    return [...filtered].sort((a, b) => {
      if (sortField === 'endpoint') {
        return sortDir === 'desc' ? b.endpoint.localeCompare(a.endpoint) : a.endpoint.localeCompare(b.endpoint)
      }
      const av = a[sortField] as number, bv = b[sortField] as number
      return sortDir === 'desc' ? bv - av : av - bv
    })
  }, [filtered, sortField, sortDir])

  const maxAvg = useMemo(() => filtered.reduce((m, s) => Math.max(m, s.avgDurationMs), 1), [filtered])

  const handleSort = (field: keyof EndpointStats) => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortField(field); setSortDir('desc') }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={2} alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 300 }}
          freeSolo
          options={endpointOptions}
          value={filterEndpoint || null}
          onInputChange={(_, v) => setFilterEndpoint(v ?? '')}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.stats.endpoint')} />}
        />
        <Typography variant="caption" color="text.secondary">
          {sorted.length} / {stats.length}
        </Typography>
      </Stack>
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
