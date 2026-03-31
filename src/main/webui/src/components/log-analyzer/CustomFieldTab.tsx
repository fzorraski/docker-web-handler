import { useState, useMemo, Fragment } from 'react'
import {
  Box, Typography, Table, TableHead, TableRow, TableCell, TableBody,
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

  const { data, total, loading } = usePaginatedFetch<CustomFieldMatch>(
    () => logService.getCustomFieldResults(analysisId, fieldName, { page, size: rowsPerPage }),
    [analysisId, fieldName, page, rowsPerPage],
  )

  const groupColumns = useMemo(() => {
    const keys = new Set<string>()
    data.forEach(m => Object.keys(m.groups).forEach(k => keys.add(k)))
    return Array.from(keys)
  }, [data])

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
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
                  <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => setExpandedRow(expandedRow === globalIdx ? null : globalIdx)}>
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
                      <TableCell colSpan={3 + groupColumns.length} sx={{ position: 'relative', bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
                        <Tooltip title="Copy" arrow>
                          <IconButton size="small" sx={{ position: 'absolute', top: 4, right: 4, opacity: 0.4, '&:hover': { opacity: 1 } }}
                            onClick={(e) => { e.stopPropagation(); navigator.clipboard.writeText(match.fullMessage).catch(() => {}) }}>
                            <ContentCopy sx={{ fontSize: 14 }} />
                          </IconButton>
                        </Tooltip>
                        <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', pr: 4 }}>
                          {match.fullMessage}
                        </Typography>
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
