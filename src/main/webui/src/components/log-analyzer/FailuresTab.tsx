import { useState, useEffect, useMemo, useRef, Fragment } from 'react'
import {
  Autocomplete, Box, Typography, Chip, Button, Paper, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination, TextField,
  useTheme,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { formatDuration } from '../../utils/formatDuration'
import type { RepeatedFailure } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function FailuresTab({ analysisId }: { analysisId: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

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
