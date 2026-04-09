import { useState, useEffect, useMemo, Fragment } from 'react'
import {
  Alert, Box, Typography, Table, TableHead, TableRow, TableCell, TableBody,
  TableContainer, TablePagination, LinearProgress, IconButton, Tooltip,
  useTheme,
} from '@mui/material'
import { ContentCopy } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { LineLink } from './LineLink'
import type { CustomFieldMatch } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function CustomFieldTab({ analysisId, fieldName, onJumpToLine }: { analysisId: string; fieldName: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const { theadBg, theadColor } = useTableHeaderTheme()
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedRow, setExpandedRow] = useState<number | null>(null)

  useEffect(() => { setPage(0); setExpandedRow(null) }, [analysisId, fieldName])

  const { data, total, loading, error } = usePaginatedFetch<CustomFieldMatch>(
    () => logService.getCustomFieldResults(analysisId, fieldName, { page, size: rowsPerPage }),
    [analysisId, fieldName, page, rowsPerPage],
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
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: theadBg, '& th': { color: theadColor } }}>
              <TableCell>{t('logAnalyzer.customFields.line')}</TableCell>
              <TableCell>{t('logAnalyzer.customFields.timestamp')}</TableCell>
              <TableCell>{t('logAnalyzer.customFields.thread')}</TableCell>
              {groupColumns.map(col => (
                <TableCell key={col}>{col}</TableCell>
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
                              onClick={(e) => { e.stopPropagation(); navigator.clipboard.writeText(match.fullMessage).catch(() => {}) }}>
                              <ContentCopy sx={{ fontSize: 14 }} />
                            </IconButton>
                          </Tooltip>
                          <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', pr: 4 }}>
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
