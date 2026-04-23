import { useState, useEffect } from 'react'
import {
  Autocomplete, Box, Typography, LinearProgress, TextField, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { LineLink } from './LineLink'
import type { OrphanJob } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function OrphanJobsTab({ analysisId, onJumpToLine }: {
  analysisId: string; onJumpToLine?: (line: number) => void
}) {
  const { t } = useTranslation()
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [filterJobName, setFilterJobName] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [jobNames, setJobNames] = useState<string[]>([])
  const [threads, setThreads] = useState<string[]>([])

  useEffect(() => {
    logService.getOrphanJobs(analysisId, { size: 5000 }).then(res => {
      setJobNames([...new Set(res.data.map(o => o.jobName))].sort())
      setThreads([...new Set(res.data.map(o => o.thread))].sort())
    }).catch(() => {})
  }, [analysisId])

  const { data: orphans, total, loading } = usePaginatedFetch<OrphanJob>(
    (signal) => logService.getOrphanJobs(analysisId, {
      jobName: filterJobName || undefined,
      thread: filterThread || undefined,
      page, size: rowsPerPage, signal,
    }),
    [analysisId, filterJobName, filterThread, page, rowsPerPage],
  )

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Typography variant="body2" color="text.secondary" mb={2}>
        {t('logAnalyzer.orphanJobs.description')}
      </Typography>

      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={jobNames}
          value={filterJobName || null}
          onChange={(_, v) => { setFilterJobName(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.jobs.name')} />}
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
              <TableCell>{t('logAnalyzer.jobs.name')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.trigger')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell>{t('logAnalyzer.orphanJobs.startTime')}</TableCell>
              {onJumpToLine && <TableCell>{t('logAnalyzer.customFields.line')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {orphans.map((o) => (
              <TableRow key={`${o.lineNumber}-${o.sourceFile}`} hover>
                <TableCell>
                  <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{o.jobName}</Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="body2" fontSize="0.8rem">{o.triggerName ?? '-'}</Typography>
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
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        showFirstButton showLastButton />
    </Box>
  )
}
