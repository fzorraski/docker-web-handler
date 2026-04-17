import { useState, useEffect, Fragment } from 'react'
import { copyToClipboard } from '../../utils/clipboard'
import {
  Autocomplete, Box, Typography, IconButton, Tooltip, LinearProgress, TextField, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination,
  useTheme,
} from '@mui/material'
import { ContentCopy } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { maskSensitiveFields, tryFormatJson } from '../../utils/jsonUtils'
import { LineLink } from './LineLink'
import type { OrphanRequest } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function OrphanRequestsTab({ analysisId, sensitiveFields, maskEnabled, onJumpToLine }: {
  analysisId: string; sensitiveFields?: string[]; maskEnabled?: boolean; onJumpToLine?: (line: number) => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [filterEndpoint, setFilterEndpoint] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [endpoints, setEndpoints] = useState<string[]>([])
  const [threads, setThreads] = useState<string[]>([])

  useEffect(() => {
    logService.getOrphanRequests(analysisId, { size: 5000 }).then(res => {
      setEndpoints([...new Set(res.data.map(o => o.endpoint))].sort())
      setThreads([...new Set(res.data.map(o => o.thread))].sort())
    }).catch(() => {})
  }, [analysisId])

  const { data: orphans, total, loading } = usePaginatedFetch<OrphanRequest>(
    (signal) => logService.getOrphanRequests(analysisId, {
      endpoint: filterEndpoint || undefined,
      thread: filterThread || undefined,
      page, size: rowsPerPage, signal,
    }),
    [analysisId, filterEndpoint, filterThread, page, rowsPerPage],
  )

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Typography variant="body2" color="text.secondary" mb={2}>
        {t('logAnalyzer.orphanRequests.description')}
      </Typography>

      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={endpoints}
          value={filterEndpoint || null}
          onChange={(_, v) => { setFilterEndpoint(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.endpoint')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={threads}
          value={filterThread || null}
          onChange={(_, v) => { setFilterThread(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.thread')} />}
        />
      </Stack>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell>{t('logAnalyzer.apiCalls.endpoint')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell>{t('logAnalyzer.orphanRequests.requestTime')}</TableCell>
              {onJumpToLine && <TableCell>{t('logAnalyzer.customFields.line')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {orphans.map((o, idx) => {
              const globalIdx = page * rowsPerPage + idx
              return (
                <Fragment key={globalIdx}>
                  <TableRow
                    hover
                    onClick={() => setExpandedRow(expandedRow === globalIdx ? null : globalIdx)}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell>
                      <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{o.endpoint}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">{o.thread}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">{o.timestamp?.replace('T', ' ') ?? '-'}</Typography>
                    </TableCell>
                    {onJumpToLine && (
                      <TableCell>
                        <LineLink line={o.lineNumber} onClick={onJumpToLine} />
                      </TableCell>
                    )}
                  </TableRow>
                  {expandedRow === globalIdx && o.payload && (
                    <TableRow>
                      <TableCell colSpan={onJumpToLine ? 4 : 3} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)', borderLeft: '3px solid #ff9800' }}>
                        <Typography variant="caption" fontWeight={600} display="block" mb={0.5}>
                          {t('logAnalyzer.orphanRequests.payload')}
                        </Typography>
                        <Box sx={{
                          fontFamily: "'JetBrains Mono', monospace",
                          fontSize: '0.75rem',
                          p: 1.5,
                          borderRadius: 1,
                          bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.04)',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-all',
                          maxHeight: 300,
                          overflow: 'auto',
                          position: 'relative',
                        }}>
                          <Tooltip title={t('containers.logs.copyAll')} arrow>
                            <IconButton
                              size="small"
                              sx={{ position: 'absolute', top: 4, right: 4, opacity: 0.6, '&:hover': { opacity: 1 } }}
                              onClick={(e) => {
                                e.stopPropagation()
                                const text = tryFormatJson(maskSensitiveFields(o.payload!, sensitiveFields ?? [], maskEnabled ?? false)).formatted
                                copyToClipboard(text).catch(() => {})
                              }}
                            >
                              <ContentCopy sx={{ fontSize: 14 }} />
                            </IconButton>
                          </Tooltip>
                          {tryFormatJson(maskSensitiveFields(o.payload, sensitiveFields ?? [], maskEnabled ?? false)).formatted}
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
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        showFirstButton showLastButton />
    </Box>
  )
}
