import { useState } from 'react'
import {
  Box, Typography, Chip, LinearProgress,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { formatDuration } from '../../utils/formatDuration'
import { LineLink } from './LineLink'
import type { JobExecution } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function JobsTab({ analysisId, onJumpToLine }: { analysisId: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)

  const { data: jobs, total, loading } = usePaginatedFetch<JobExecution>(
    () => logService.getJobs(analysisId, { page, size: rowsPerPage }),
    [analysisId, page, rowsPerPage],
  )

  const lineLink = (line: number) => onJumpToLine
    ? <LineLink line={line} onClick={onJumpToLine} />
    : null

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell>{t('logAnalyzer.jobs.name')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.trigger')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.start')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.end')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.duration')}</TableCell>
              <TableCell>{t('logAnalyzer.jobs.result')}</TableCell>
              {onJumpToLine && <TableCell>{t('logAnalyzer.customFields.line')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {jobs.map((job, i) => (
              <TableRow key={i} hover>
                <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{job.jobName}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.triggerName ?? '-'}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.thread}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.startTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.endTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Chip size="small" label={formatDuration(job.durationMs)} /></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem" color={job.result && job.result !== 'null' ? 'warning.main' : 'text.secondary'}>{job.result}</Typography></TableCell>
                {onJumpToLine && <TableCell>{lineLink(job.startLineNumber)}</TableCell>}
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
