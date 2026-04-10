import { useState, useEffect } from 'react'
import {
  Autocomplete, Box, Typography, Chip, LinearProgress, TextField, Stack,
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
  const [filterJob, setFilterJob] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [sort, setSort] = useState('time')
  const [jobNames, setJobNames] = useState<string[]>([])
  const [threads, setThreads] = useState<string[]>([])

  // Fetch distinct job names and threads once for filter dropdowns
  useEffect(() => {
    logService.getJobFilters(analysisId).then(res => {
      setJobNames(res.jobNames)
      setThreads(res.threads)
    }).catch(() => {})
  }, [analysisId])

  // Server-side sort + filter + pagination
  const { data: jobs, total, loading } = usePaginatedFetch<JobExecution>(
    (signal) => logService.getJobs(analysisId, {
      jobName: filterJob || undefined,
      thread: filterThread || undefined,
      sort,
      page, size: rowsPerPage, signal,
    }),
    [analysisId, filterJob, filterThread, sort, page, rowsPerPage],
  )

  const lineLink = (line: number) => onJumpToLine
    ? <LineLink line={line} onClick={onJumpToLine} />
    : null

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Autocomplete
          size="small"
          sx={{ minWidth: 250 }}
          options={jobNames}
          value={filterJob || null}
          onChange={(_, v) => { setFilterJob(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.jobs.name')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 200 }}
          options={threads}
          value={filterThread || null}
          onChange={(_, v) => { setFilterThread(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.thread')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 180 }}
          disableClearable
          options={[
            { value: 'time', label: t('logAnalyzer.apiCalls.sortByTime') },
            { value: 'duration', label: t('logAnalyzer.apiCalls.sortByDuration') },
            { value: 'name', label: t('logAnalyzer.jobs.sortByName') },
          ]}
          value={{ value: sort, label: sort === 'duration' ? t('logAnalyzer.apiCalls.sortByDuration') : sort === 'name' ? t('logAnalyzer.jobs.sortByName') : t('logAnalyzer.apiCalls.sortByTime') }}
          onChange={(_, v) => setSort(v?.value ?? 'time')}
          getOptionLabel={(o) => o.label}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.apiCalls.sort')} />}
        />
      </Stack>
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
                <TableCell><Chip size="small" label={formatDuration(job.durationMs)} color={job.durationMs >= 10000 ? 'warning' : 'default'} /></TableCell>
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
