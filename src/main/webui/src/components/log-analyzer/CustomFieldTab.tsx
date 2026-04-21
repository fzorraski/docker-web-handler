import { useState, useEffect, useMemo, useCallback, Fragment } from 'react'
import { copyToClipboard } from '../../utils/clipboard'
import {
  Alert, Autocomplete, Box, Typography, Table, TableHead, TableRow, TableCell, TableBody,
  TableContainer, TablePagination, TableSortLabel, LinearProgress, IconButton, Tooltip,
  TextField, Stack, InputAdornment,
  useTheme,
} from '@mui/material'
import { ContentCopy, Download, Search } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { formatBytes } from '../../utils/format'
import { LineLink } from './LineLink'
import type { CustomFieldMatch } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function CustomFieldTab({ analysisId, fieldName, onJumpToLine }: {
  analysisId: string; fieldName: string; onJumpToLine?: (line: number) => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedRow, setExpandedRow] = useState<number | null>(null)
  const [searchInput, setSearchInput] = useState('')
  const [threadFilter, setThreadFilter] = useState('')
  const [threads, setThreads] = useState<string[]>([])
  const [sort, setSort] = useState('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const debouncedSearch = useDebouncedValue(searchInput, 350)

  const handleSort = (field: string, defaultDir: 'asc' | 'desc' = 'asc') => {
    if (sort === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSort(field); setSortDir(defaultDir) }
    setPage(0)
  }

  const handleDownloadLine = useCallback(async (lineNumber: number) => {
    try {
      const blob = await logService.downloadLineContent(analysisId, lineNumber)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `log-line-${lineNumber}.txt`
      a.click()
      URL.revokeObjectURL(url)
    } catch { /* download failed */ }
  }, [analysisId])

  useEffect(() => {
    logService.getThreads(analysisId).then(list => setThreads(list.map(th => th.thread))).catch(() => {})
  }, [analysisId])

  useEffect(() => { setPage(0); setExpandedRow(null) }, [analysisId, fieldName])
  useEffect(() => { setPage(0); setExpandedRow(null) }, [debouncedSearch, threadFilter])

  const { data, total, loading, error } = usePaginatedFetch<CustomFieldMatch>(
    (signal) => logService.getCustomFieldResults(analysisId, fieldName, {
      page, size: rowsPerPage,
      search: debouncedSearch || undefined,
      thread: threadFilter || undefined,
      sort: sort || undefined,
      sortDir: sort ? sortDir : undefined, signal,
    }),
    [analysisId, fieldName, page, rowsPerPage, debouncedSearch, threadFilter, sort, sortDir],
  )

  const groupColumns = useMemo(() => {
    const keys = new Set<string>()
    data.forEach(m => Object.keys(m.groups ?? {}).forEach(k => keys.add(k)))
    return Array.from(keys)
  }, [data])

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}

      <Stack direction="row" spacing={2} mb={2} alignItems="center" flexWrap="wrap" useFlexGap>
        <TextField
          size="small"
          placeholder={t('logAnalyzer.customFields.search')}
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          sx={{ minWidth: 220 }}
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
        {threads.length > 0 && (
          <Autocomplete
            size="small"
            sx={{ minWidth: 220 }}
            options={threads}
            value={threadFilter || null}
            onChange={(_, v) => setThreadFilter(v ?? '')}
            renderInput={(params) => <TextField {...params} label={t('logAnalyzer.customFields.filterByThread')} />}
          />
        )}
      </Stack>

      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: theadBg, '& th': { color: theadColor } }}>
              <TableCell>
                <TableSortLabel active={sort === 'line'} direction={sort === 'line' ? sortDir : 'asc'}
                  onClick={() => handleSort('line')} sx={theadSortSx}>
                  {t('logAnalyzer.customFields.line')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'timestamp'} direction={sort === 'timestamp' ? sortDir : 'asc'}
                  onClick={() => handleSort('timestamp')} sx={theadSortSx}>
                  {t('logAnalyzer.customFields.timestamp')}
                </TableSortLabel>
              </TableCell>
              <TableCell>
                <TableSortLabel active={sort === 'thread'} direction={sort === 'thread' ? sortDir : 'asc'}
                  onClick={() => handleSort('thread')} sx={theadSortSx}>
                  {t('logAnalyzer.customFields.thread')}
                </TableSortLabel>
              </TableCell>
              {groupColumns.map(col => (
                <TableCell key={col}>
                  <TableSortLabel active={sort === col} direction={sort === col ? sortDir : 'asc'}
                    onClick={() => handleSort(col)} sx={theadSortSx}>
                    {col}
                  </TableSortLabel>
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {data.map((match, i) => {
              const globalIdx = page * rowsPerPage + i
              return (
                <Fragment key={globalIdx}>
                  <TableRow
                    hover
                    sx={{
                      cursor: 'pointer',
                      ...(expandedRow === globalIdx && {
                        '& td': { borderBottom: 'none' },
                      }),
                    }}
                    onClick={() => setExpandedRow(expandedRow === globalIdx ? null : globalIdx)}
                  >
                    <TableCell>
                      {onJumpToLine
                        ? <LineLink line={match.lineNumber} onClick={onJumpToLine} />
                        : <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{match.lineNumber}</Typography>
                      }
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">
                        {match.timestamp?.replace('T', ' ') ?? '-'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" fontSize="0.8rem">
                        {match.thread ?? '-'}
                      </Typography>
                    </TableCell>
                    {groupColumns.map(col => (
                      <TableCell key={col}>
                        <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">
                          {match.groups[col] ?? '-'}
                        </Typography>
                      </TableCell>
                    ))}
                  </TableRow>
                  {expandedRow === globalIdx && (
                    <TableRow>
                      <TableCell colSpan={3 + groupColumns.length} sx={{ p: 0, border: 'none' }}>
                        <Box sx={{
                          position: 'relative',
                          m: 1,
                          mt: 0,
                          p: 2,
                          borderRadius: 1,
                          border: `1px solid ${isDark ? 'rgba(255,109,0,0.3)' : 'rgba(255,109,0,0.25)'}`,
                          borderLeft: `4px solid #FF6D00`,
                          bgcolor: isDark ? 'rgba(255,109,0,0.06)' : 'rgba(255,109,0,0.03)',
                          maxHeight: 300,
                          overflow: 'auto',
                        }}>
                          <Tooltip title={t('logAnalyzer.customFields.copy')} arrow>
                            <IconButton size="small" sx={{ position: 'absolute', top: 6, right: 6, opacity: 0.5, '&:hover': { opacity: 1 } }}
                              onClick={(e) => { e.stopPropagation(); copyToClipboard(match.fullMessage).catch(() => {}) }}>
                              <ContentCopy sx={{ fontSize: 14 }} />
                            </IconButton>
                          </Tooltip>
                          <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', pr: 4 }}>
                            {match.fullMessageTruncated && (
                              <Tooltip title={`${t('logAnalyzer.rawLog.lineTruncated')} (${formatBytes(match.fullMessageSize)})`} arrow>
                                <Box component="span" onClick={(e) => { e.stopPropagation(); handleDownloadLine(match.lineNumber) }}
                                  sx={{ width: 18, flexShrink: 0, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: '#FF6D00', verticalAlign: 'middle' }}>
                                  <Download sx={{ fontSize: 12 }} />
                                </Box>
                              </Tooltip>
                            )}
                            {match.fullMessage}
                          </Typography>
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
