import { useState, useEffect, useMemo, useRef, Fragment } from 'react'
import {
  Autocomplete, Box, Typography, Chip, Button, Paper, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination, Collapse, TextField, InputAdornment,
  useTheme,
} from '@mui/material'
import { ExpandMore, ExpandLess, ContentCopy, Search } from '@mui/icons-material'
import { IconButton, Tooltip } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { LineLink } from './LineLink'
import type { ExceptionLocationSummary } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

const TYPE_COLORS: string[] = [
  '#e53935', '#8e24aa', '#1e88e5', '#43a047', '#fb8c00',
  '#00acc1', '#d81b60', '#3949ab', '#7cb342', '#f4511e',
  '#6d4c41', '#546e7a', '#c0ca33', '#00897b', '#5e35b1',
]

export function ExceptionAnalysisTab({ analysisId, onJumpToLine }: { analysisId: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [allLocations, setAllLocations] = useState<ExceptionLocationSummary[]>([])
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const [traceOpen, setTraceOpen] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [filterType, setFilterType] = useState<string | null>(null)
  const [searchText, setSearchText] = useState('')
  const [occurrences, setOccurrences] = useState<import('../../services/logAnalyzerService').ExceptionOccurrence[]>([])
  const [occTotal, setOccTotal] = useState(0)
  const [occPage, setOccPage] = useState(0)
  const [occLoading, setOccLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getExceptionAnalysis(analysisId, { page: 0, size: 500 }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setAllLocations(r.data)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId])

  const uniqueTypes = useMemo(() => {
    const set = new Set<string>()
    allLocations.forEach(loc => set.add(loc.exceptionType))
    return Array.from(set).sort()
  }, [allLocations])

  const typeColorMap = useMemo(() => {
    const map: Record<string, string> = {}
    uniqueTypes.forEach((type, i) => {
      map[type] = TYPE_COLORS[i % TYPE_COLORS.length]
    })
    return map
  }, [uniqueTypes])

  const filtered = useMemo(() => {
    let result = allLocations
    if (filterType) result = result.filter(loc => loc.exceptionType === filterType)
    const term = searchText.trim().toLowerCase()
    if (term) {
      result = result.filter(loc =>
        loc.exceptionType.toLowerCase().includes(term)
        || loc.origin.toLowerCase().includes(term)
        || loc.originClass.toLowerCase().includes(term)
        || loc.method.toLowerCase().includes(term)
        || loc.sourceFile.toLowerCase().includes(term))
    }
    return result
  }, [allLocations, filterType, searchText])

  const totalOccurrences = useMemo(() => filtered.reduce((sum, loc) => sum + loc.count, 0), [filtered])
  const uniqueLocationCount = useMemo(() => filtered.length, [filtered])

  const paged = filtered.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)

  const countColor = (count: number): 'default' | 'warning' | 'error' => {
    if (count >= 50) return 'error'
    if (count >= 10) return 'warning'
    return 'default'
  }

  const toggleTrace = (key: string) => {
    setTraceOpen(prev => ({ ...prev, [key]: !prev[key] }))
  }

  const fetchOccurrences = (origin: string, pg: number) => {
    setOccLoading(true)
    logService.getExceptionOccurrences(analysisId, origin, { page: pg, size: 25 })
      .then(r => { setOccurrences(r.data); setOccTotal(r.total); setOccPage(r.page) })
      .catch(() => { setOccurrences([]); setOccTotal(0) })
      .finally(() => setOccLoading(false))
  }

  const handleExpandRow = (idx: number, loc: ExceptionLocationSummary) => {
    if (expandedIdx === idx) {
      setExpandedIdx(null); setOccurrences([]); setOccTotal(0); setOccPage(0)
    } else {
      const origin = `${loc.exceptionType}:${loc.origin}`
      setExpandedIdx(idx); setOccPage(0); setTraceOpen({}); fetchOccurrences(origin, 0)
    }
  }

  // Reset page when filter changes
  useEffect(() => {
    setPage(0)
    setExpandedIdx(null)
  }, [filterType, searchText])

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      {/* Summary */}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.exceptionAnalysis.totalOccurrences')}</Typography>
          <Typography variant="h6" fontWeight={700} color="error.main">{totalOccurrences.toLocaleString()}</Typography>
        </Paper>
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.exceptionAnalysis.uniqueTypes')}</Typography>
          <Typography variant="h6" fontWeight={700}>{uniqueTypes.length}</Typography>
        </Paper>
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.exceptionAnalysis.uniqueLocations')}</Typography>
          <Typography variant="h6" fontWeight={700}>{uniqueLocationCount}</Typography>
        </Paper>
        <Autocomplete
          size="small"
          sx={{ minWidth: 280 }}
          options={uniqueTypes}
          value={filterType}
          onChange={(_, v) => setFilterType(v)}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.exceptionAnalysis.filterByType')} />}
        />
        <TextField
          size="small"
          placeholder={t('logAnalyzer.exceptionAnalysis.search')}
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          sx={{ minWidth: 250 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search sx={{ fontSize: 18, color: 'text.disabled' }} />
                </InputAdornment>
              ),
            },
          }}
        />
      </Stack>

      {filtered.length === 0 && !loading && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          {t('logAnalyzer.exceptionAnalysis.noExceptions')}
        </Typography>
      )}

      {filtered.length > 0 && (
        <>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
                  <TableCell>{t('logAnalyzer.exceptionAnalysis.exceptionType')}</TableCell>
                  <TableCell>{t('logAnalyzer.exceptionAnalysis.origin')}</TableCell>
                  <TableCell>{t('logAnalyzer.exceptionAnalysis.count')}</TableCell>
                  <TableCell>{t('logAnalyzer.exceptionAnalysis.firstSeen')}</TableCell>
                  <TableCell>{t('logAnalyzer.exceptionAnalysis.lastSeen')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {paged.map((loc, i) => {
                  const globalIdx = page * rowsPerPage + i
                  const isExpanded = expandedIdx === globalIdx

                  return (
                    <Fragment key={globalIdx}>
                      <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => handleExpandRow(globalIdx, loc)}>
                        <TableCell>
                          <Chip
                            size="small"
                            label={loc.exceptionType}
                            sx={{
                              bgcolor: typeColorMap[loc.exceptionType] ?? TYPE_COLORS[0],
                              color: '#fff',
                              fontFamily: "'JetBrains Mono', monospace",
                              fontSize: '0.75rem',
                              fontWeight: 600,
                            }}
                          />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{loc.origin}</Typography>
                        </TableCell>
                        <TableCell>
                          <Chip size="small" label={loc.count.toLocaleString()} color={countColor(loc.count)} />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontSize="0.8rem">{loc.firstSeen?.replace('T', ' ')}</Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontSize="0.8rem">{loc.lastSeen?.replace('T', ' ')}</Typography>
                        </TableCell>
                      </TableRow>
                      {isExpanded && (
                        <TableRow>
                          <TableCell colSpan={5} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
                            {occLoading && <LinearProgress sx={{ mb: 1 }} />}
                            <Box sx={{ maxHeight: 500, overflowY: 'auto' }}>
                              {occurrences.map((occ, oi) => {
                                const traceKey = `${globalIdx}-${oi}`
                                const isTraceOpen = traceOpen[traceKey] ?? false

                                return (
                                  <Box key={oi} sx={{ py: 0.5, borderBottom: '1px solid', borderColor: 'divider' }}>
                                    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                                      <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" color="text.secondary">
                                        [{occ.timestamp?.replace('T', ' ') ?? '-'}]
                                      </Typography>
                                      <Typography variant="body2" fontSize="0.75rem" color="text.secondary">
                                        {t('logAnalyzer.exceptionAnalysis.logLine')}:
                                      </Typography>
                                      {onJumpToLine ? (
                                        <LineLink line={occ.logLineNumber} onClick={onJumpToLine} />
                                      ) : (
                                        <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem">
                                          L{occ.logLineNumber}
                                        </Typography>
                                      )}
                                      {occ.message && (
                                        <Typography variant="body2" fontSize="0.75rem" sx={{ ml: 1 }}>
                                          {occ.message}
                                        </Typography>
                                      )}
                                      {occ.stackTrace.length > 0 && (
                                        <>
                                        <Button
                                          size="small"
                                          variant="text"
                                          onClick={(e) => { e.stopPropagation(); toggleTrace(traceKey) }}
                                          endIcon={isTraceOpen ? <ExpandLess /> : <ExpandMore />}
                                          sx={{ fontSize: '0.7rem', textTransform: 'none', minWidth: 'auto', py: 0 }}
                                        >
                                          {isTraceOpen ? t('logAnalyzer.exceptionAnalysis.hideTrace') : t('logAnalyzer.exceptionAnalysis.showTrace')}
                                        </Button>
                                        <Tooltip title={t('logAnalyzer.exceptionAnalysis.copyOccurrence')} arrow>
                                          <IconButton size="small" onClick={(e) => {
                                            e.stopPropagation()
                                            const text = `${occ.exceptionType}: ${occ.message ?? ''}\nTimestamp: ${occ.timestamp?.replace('T', ' ') ?? '-'}\nOrigin: ${occ.originClass}.${occ.method}(${occ.sourceFile}:${occ.sourceLine})\nLine: ${occ.logLineNumber}\n\n${occ.stackTrace.join('\n')}`
                                            navigator.clipboard.writeText(text)
                                          }}>
                                            <ContentCopy sx={{ fontSize: 14 }} />
                                          </IconButton>
                                        </Tooltip>
                                        </>
                                      )}
                                    </Stack>
                                    <Collapse in={isTraceOpen}>
                                      <Box sx={{ mt: 0.5, position: 'relative' }}>
                                        <Tooltip title={t('logAnalyzer.exceptionAnalysis.copyTrace')} arrow>
                                          <IconButton size="small"
                                            sx={{ position: 'absolute', top: 4, right: 4, opacity: 0.6, '&:hover': { opacity: 1 } }}
                                            onClick={(e) => { e.stopPropagation(); navigator.clipboard.writeText(occ.stackTrace.join('\n')) }}>
                                            <ContentCopy sx={{ fontSize: 14 }} />
                                          </IconButton>
                                        </Tooltip>
                                        <Box sx={{
                                          p: 1, borderRadius: 1,
                                          bgcolor: isDark ? 'rgba(0,0,0,0.4)' : 'rgba(0,0,0,0.05)',
                                          fontFamily: "'JetBrains Mono', monospace",
                                          fontSize: '0.7rem',
                                          whiteSpace: 'pre',
                                          overflowX: 'auto',
                                          maxHeight: 300,
                                          overflowY: 'auto',
                                        }}>
                                          {occ.stackTrace.join('\n')}
                                        </Box>
                                      </Box>
                                    </Collapse>
                                  </Box>
                                )
                              })}
                            </Box>
                            {occTotal > 25 && (
                              <TablePagination component="div" count={occTotal} page={occPage}
                                onPageChange={(_, p) => { setOccPage(p); fetchOccurrences(`${loc.exceptionType}:${loc.origin}`, p) }}
                                rowsPerPage={25} rowsPerPageOptions={[25]} showFirstButton showLastButton
                                sx={{ borderTop: '1px solid', borderColor: 'divider', overflow: 'visible' }} />
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
          <TablePagination component="div" count={filtered.length} page={page} onPageChange={(_, p) => setPage(p)}
            rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
            showFirstButton showLastButton />
        </>
      )}
    </Box>
  )
}
