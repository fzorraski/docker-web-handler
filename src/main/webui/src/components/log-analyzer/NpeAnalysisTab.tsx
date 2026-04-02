import { useState, useEffect, useMemo, useRef, Fragment } from 'react'
import {
  Box, Typography, Chip, Button, Paper, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination, Collapse,
  useTheme,
} from '@mui/material'
import { ExpandMore, ExpandLess, ContentCopy } from '@mui/icons-material'
import { IconButton, Tooltip } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { LineLink } from './LineLink'
import type { NpeLocationSummary } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function NpeAnalysisTab({ analysisId, onJumpToLine }: { analysisId: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [allLocations, setAllLocations] = useState<NpeLocationSummary[]>([])
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const [traceOpen, setTraceOpen] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getNpeAnalysis(analysisId, { page: 0, size: 500 }).then((r) => {
      if (gen !== fetchGenRef.current) return
      setAllLocations(r.data)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId])

  const totalOccurrences = useMemo(() => allLocations.reduce((sum, loc) => sum + loc.count, 0), [allLocations])

  const paged = allLocations.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)

  const countColor = (count: number): 'default' | 'warning' | 'error' => {
    if (count >= 50) return 'error'
    if (count >= 10) return 'warning'
    return 'default'
  }

  const toggleTrace = (key: string) => {
    setTraceOpen(prev => ({ ...prev, [key]: !prev[key] }))
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      {/* Summary */}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.npeAnalysis.totalOccurrences')}</Typography>
          <Typography variant="h6" fontWeight={700} color="error.main">{totalOccurrences.toLocaleString()}</Typography>
        </Paper>
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.npeAnalysis.uniqueLocations')}</Typography>
          <Typography variant="h6" fontWeight={700}>{allLocations.length}</Typography>
        </Paper>
      </Stack>

      {allLocations.length === 0 && !loading && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          {t('logAnalyzer.npeAnalysis.noNpes')}
        </Typography>
      )}

      {allLocations.length > 0 && (
        <>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
                  <TableCell>{t('logAnalyzer.npeAnalysis.origin')}</TableCell>
                  <TableCell>{t('logAnalyzer.npeAnalysis.count')}</TableCell>
                  <TableCell>{t('logAnalyzer.npeAnalysis.firstSeen')}</TableCell>
                  <TableCell>{t('logAnalyzer.npeAnalysis.lastSeen')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {paged.map((loc, i) => {
                  const globalIdx = page * rowsPerPage + i
                  const isExpanded = expandedIdx === globalIdx

                  return (
                    <Fragment key={globalIdx}>
                      <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => {
                        setExpandedIdx(isExpanded ? null : globalIdx)
                      }}>
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
                          <TableCell colSpan={4} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
                            <Box sx={{ maxHeight: 500, overflowY: 'auto' }}>
                              {loc.occurrences.map((occ, oi) => {
                                const traceKey = `${globalIdx}-${oi}`
                                const isTraceOpen = traceOpen[traceKey] ?? false

                                return (
                                  <Box key={oi} sx={{ py: 0.5, borderBottom: '1px solid', borderColor: 'divider' }}>
                                    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                                      <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" color="text.secondary">
                                        [{occ.timestamp?.replace('T', ' ') ?? '-'}]
                                      </Typography>
                                      <Typography variant="body2" fontSize="0.75rem" color="text.secondary">
                                        {t('logAnalyzer.npeAnalysis.logLine')}:
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
                                          {isTraceOpen ? t('logAnalyzer.npeAnalysis.hideTrace') : t('logAnalyzer.npeAnalysis.showTrace')}
                                        </Button>
                                        <Tooltip title={t('logAnalyzer.npeAnalysis.copyOccurrence')} arrow>
                                          <IconButton size="small" onClick={(e) => {
                                            e.stopPropagation()
                                            const text = `NullPointerException: ${occ.message ?? ''}\nTimestamp: ${occ.timestamp?.replace('T', ' ') ?? '-'}\nOrigin: ${occ.originClass}.${occ.method}(${occ.sourceFile}:${occ.sourceLine})\nLine: ${occ.logLineNumber}\n\n${occ.stackTrace.join('\n')}`
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
                                        <Tooltip title={t('logAnalyzer.npeAnalysis.copyTrace')} arrow>
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
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination component="div" count={allLocations.length} page={page} onPageChange={(_, p) => setPage(p)}
            rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
            showFirstButton showLastButton />
        </>
      )}
    </Box>
  )
}
