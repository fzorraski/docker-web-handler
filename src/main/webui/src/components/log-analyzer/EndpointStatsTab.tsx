import { useState, useEffect, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Chip, LinearProgress, TextField, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer, TableSortLabel,
  Menu, MenuItem, ListItemIcon, ListItemText,
  Paper, IconButton, Tooltip,
  useTheme,
} from '@mui/material'
import { QueryStats, InfoOutlined, Download } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { formatDuration } from '../../utils/formatDuration'
import type { EndpointStats } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

function durationColor(ms: number): string {
  if (ms < 500) return 'success.main'
  if (ms < 2000) return 'warning.main'
  if (ms < 5000) return '#ed6c02'
  return 'error.main'
}

function healthBorderColor(s: EndpointStats): string {
  const slowPercent = s.callCount > 0 ? s.slowCount / s.callCount : 0
  if (slowPercent > 0.3 || s.avgDurationMs >= 5000) return '#d32f2f'
  if (slowPercent > 0.1 || s.avgDurationMs >= 2000) return '#ed6c02'
  if (slowPercent > 0.03 || s.avgDurationMs >= 500) return '#ffc107'
  return '#4caf50'
}

export function EndpointStatsTab({ analysisId, onViewInsights }: { analysisId: string; onViewInsights?: (endpoint: string) => void }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; endpoint: string } | null>(null)

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
  const maxP95 = useMemo(() => filtered.reduce((m, s) => Math.max(m, s.p95DurationMs), 1), [filtered])
  const barMax = useMemo(() => Math.max(maxAvg, maxP95), [maxAvg, maxP95])

  const summary = useMemo(() => {
    const totalCalls = stats.reduce((sum, s) => sum + s.callCount, 0)
    const totalSlow = stats.reduce((sum, s) => sum + s.slowCount, 0)
    const weightedAvg = totalCalls > 0
      ? stats.reduce((sum, s) => sum + s.avgDurationMs * s.callCount, 0) / totalCalls
      : 0
    return { totalCalls, totalSlow, weightedAvg }
  }, [stats])

  const handleSort = (field: keyof EndpointStats) => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortField(field); setSortDir('desc') }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}

      {/* Summary cards */}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap>
        <Paper elevation={0} sx={{ px: 2, py: 1, border: '1px solid', borderColor: 'divider' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.stats.totalCalls')}</Typography>
          <Typography variant="h6" fontWeight={700}>{summary.totalCalls.toLocaleString()}</Typography>
        </Paper>
        <Paper elevation={0} sx={{ px: 2, py: 1, border: '1px solid', borderColor: 'divider' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.stats.weightedAvg')}</Typography>
          <Typography variant="h6" fontWeight={700} color={durationColor(summary.weightedAvg)}>
            {formatDuration(Math.round(summary.weightedAvg))}
          </Typography>
        </Paper>
        <Paper elevation={0} sx={{ px: 2, py: 1, border: '1px solid', borderColor: 'divider' }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.stats.totalSlow')}</Typography>
          <Typography variant="h6" fontWeight={700} color={summary.totalSlow > 0 ? 'error.main' : 'text.primary'}>
            {summary.totalSlow.toLocaleString()}
          </Typography>
        </Paper>
      </Stack>

      <Stack direction="row" spacing={2} mb={2} alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 300 }}
          freeSolo
          options={endpointOptions}
          value={filterEndpoint || null}
          onInputChange={(_, v) => setFilterEndpoint(v ?? '')}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.stats.filterEndpoint')} />}
        />
        <Typography variant="caption" color="text.secondary">
          {sorted.length} / {stats.length}
        </Typography>
      </Stack>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell>
                <TableSortLabel active={sortField === 'endpoint'} direction={sortField === 'endpoint' ? sortDir : 'asc'} onClick={() => handleSort('endpoint')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.endpoint')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'callCount'} direction={sortField === 'callCount' ? sortDir : 'asc'} onClick={() => handleSort('callCount')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.count')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'avgDurationMs'} direction={sortField === 'avgDurationMs' ? sortDir : 'asc'} onClick={() => handleSort('avgDurationMs')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.avg')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'minDurationMs'} direction={sortField === 'minDurationMs' ? sortDir : 'asc'} onClick={() => handleSort('minDurationMs')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.min')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'maxDurationMs'} direction={sortField === 'maxDurationMs' ? sortDir : 'asc'} onClick={() => handleSort('maxDurationMs')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.max')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'p95DurationMs'} direction={sortField === 'p95DurationMs' ? sortDir : 'asc'} onClick={() => handleSort('p95DurationMs')} sx={headerTheme.theadSortSx}>
                  P95
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sortField === 'slowCount'} direction={sortField === 'slowCount' ? sortDir : 'asc'} onClick={() => handleSort('slowCount')} sx={headerTheme.theadSortSx}>
                  {t('logAnalyzer.stats.slow')}
                </TableSortLabel>
              </TableCell>
              <TableCell width="18%">
                <Stack direction="row" spacing={0.5} alignItems="center" justifyContent="flex-end">
                  <Tooltip title={t('logAnalyzer.stats.barHelp')} arrow placement="left">
                    <InfoOutlined sx={{ fontSize: 15, color: 'text.secondary', cursor: 'help' }} />
                  </Tooltip>
                  <Tooltip title={t('logAnalyzer.compare.export')} arrow>
                    <IconButton size="small" onClick={() => window.open(logService.getStatsExportUrl(analysisId))} sx={{ opacity: 0.4, '&:hover': { opacity: 1 } }}>
                      <Download sx={{ fontSize: 15 }} />
                    </IconButton>
                  </Tooltip>
                </Stack>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sorted.map((s) => (
              <TableRow key={s.endpoint} hover
                sx={{
                  cursor: onViewInsights ? 'context-menu' : undefined,
                  borderLeft: `3px solid ${healthBorderColor(s)}`,
                  '&:hover .insights-btn': { opacity: 1 },
                }}
                onContextMenu={onViewInsights ? (e) => { e.preventDefault(); setContextMenu({ x: e.clientX, y: e.clientY, endpoint: s.endpoint }) } : undefined}
              >
                <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{s.endpoint}</Typography></TableCell>
                <TableCell>{s.callCount}</TableCell>
                <TableCell>
                  <Typography variant="body2" color={durationColor(s.avgDurationMs)} fontWeight={500}>
                    {formatDuration(Math.round(s.avgDurationMs))}
                  </Typography>
                </TableCell>
                <TableCell>{formatDuration(s.minDurationMs)}</TableCell>
                <TableCell>{formatDuration(s.maxDurationMs)}</TableCell>
                <TableCell>
                  <Typography variant="body2" color={durationColor(s.p95DurationMs)} fontWeight={500}>
                    {formatDuration(s.p95DurationMs)}
                  </Typography>
                </TableCell>
                <TableCell>
                  {s.slowCount > 0 ? (
                    <Chip size="small" label={`${s.slowCount} (${Math.round((s.slowCount / s.callCount) * 100)}%)`} color="error" />
                  ) : (
                    <Typography variant="body2" color="text.secondary">0</Typography>
                  )}
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    <Tooltip title={`${t('logAnalyzer.stats.avg')}: ${formatDuration(Math.round(s.avgDurationMs))} | P95: ${formatDuration(s.p95DurationMs)}`} arrow placement="left">
                      <Box sx={{ flex: 1, height: 8, borderRadius: 4, bgcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)', position: 'relative', overflow: 'hidden' }}>
                        <Box sx={{ position: 'absolute', top: 0, left: 0, height: '100%', borderRadius: 4, bgcolor: 'error.main', opacity: 0.25, width: `${(s.p95DurationMs / barMax) * 100}%`, transition: 'width 0.3s' }} />
                        <Box sx={{ position: 'absolute', top: 0, left: 0, height: '100%', borderRadius: 4, bgcolor: 'primary.main', width: `${(s.avgDurationMs / barMax) * 100}%`, transition: 'width 0.3s' }} />
                      </Box>
                    </Tooltip>
                    {onViewInsights && (
                      <IconButton
                        size="small"
                        className="insights-btn"
                        onClick={(e) => { e.stopPropagation(); onViewInsights(s.endpoint) }}
                        sx={{ opacity: 0, transition: 'opacity 0.15s', p: 0.25 }}
                      >
                        <QueryStats sx={{ fontSize: 16 }} />
                      </IconButton>
                    )}
                  </Stack>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {onViewInsights && (
        <Menu
          open={contextMenu !== null}
          onClose={() => setContextMenu(null)}
          anchorReference="anchorPosition"
          anchorPosition={contextMenu ? { top: contextMenu.y, left: contextMenu.x } : undefined}
        >
          <MenuItem onClick={() => { if (contextMenu) onViewInsights(contextMenu.endpoint); setContextMenu(null) }}>
            <ListItemIcon><QueryStats fontSize="small" /></ListItemIcon>
            <ListItemText>{t('logAnalyzer.insights.viewPerformance')}</ListItemText>
          </MenuItem>
        </Menu>
      )}
    </Box>
  )
}
