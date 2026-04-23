import { useState, useEffect } from 'react'
import {
  Autocomplete, Box, Typography, Chip, LinearProgress, TextField, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination, TableSortLabel,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { formatDuration } from '../../utils/formatDuration'
import { LineLink } from './LineLink'
import type { JobExecution } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type SortField = 'time' | 'duration' | 'name'
type SortDir = 'asc' | 'desc'

export function JobsTab({ analysisId, onJumpToLine }: { analysisId: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const headerTheme = useTableHeaderTheme()

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [filterJob, setFilterJob] = useState('')
  const [filterThread, setFilterThread] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [sort, setSort] = useState<SortField>('time')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [jobNames, setJobNames] = useState<string[]>([])
  const [threads, setThreads] = useState<string[]>([])

  useEffect(() => {
    logService.getJobFilters(analysisId).then(res => {
      setJobNames(res.jobNames)
      setThreads(res.threads)
    }).catch(() => {})
  }, [analysisId])

  const { data: jobs, total, loading } = usePaginatedFetch<JobExecution>(
    (signal) => logService.getJobs(analysisId, {
      jobName: filterJob || undefined,
      thread: filterThread || undefined,
      status: filterStatus || undefined,
      sort,
      sortDir,
      page, size: rowsPerPage, signal,
    }),
    [analysisId, filterJob, filterThread, filterStatus, sort, sortDir, page, rowsPerPage],
  )

  const handleSort = (field: SortField) => {
    if (sort === field) {
      setSortDir(prev => prev === 'asc' ? 'desc' : 'asc')
    } else {
      setSort(field)
      setSortDir(field === 'duration' ? 'desc' : 'asc')
    }
    setPage(0)
  }

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
          sx={{ minWidth: 160 }}
          options={[
            { value: 'success', label: t('logAnalyzer.jobs.statusSuccess') },
            { value: 'failed', label: t('logAnalyzer.jobs.statusFailed') },
          ]}
          value={filterStatus ? { value: filterStatus, label: filterStatus === 'failed' ? t('logAnalyzer.jobs.statusFailed') : t('logAnalyzer.jobs.statusSuccess') } : null}
          onChange={(_, v) => { setFilterStatus(v?.value ?? ''); setPage(0) }}
          getOptionLabel={(o) => o.label}
          isOptionEqualToValue={(o, v) => o.value === v.value}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.jobs.result')} />}
        />
      </Stack>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
              <TableCell sortDirection={sort === 'name' ? sortDir : false}>
                <TableSortLabel active={sort === 'name'} direction={sort === 'name' ? sortDir : 'asc'} onClick={() => handleSort('name')}
                  sx={{ color: 'inherit !important', '& .MuiTableSortLabel-icon': { color: 'inherit !important' } }}>
                  {t('logAnalyzer.jobs.name')}
                </TableSortLabel>
              </TableCell>
              <TableCell>{t('logAnalyzer.jobs.trigger')}</TableCell>
              <TableCell>{t('logAnalyzer.apiCalls.thread')}</TableCell>
              <TableCell sortDirection={sort === 'time' ? sortDir : false}>
                <TableSortLabel active={sort === 'time'} direction={sort === 'time' ? sortDir : 'asc'} onClick={() => handleSort('time')}
                  sx={{ color: 'inherit !important', '& .MuiTableSortLabel-icon': { color: 'inherit !important' } }}>
                  {t('logAnalyzer.jobs.start')}
                </TableSortLabel>
              </TableCell>
              <TableCell>{t('logAnalyzer.jobs.end')}</TableCell>
              <TableCell sortDirection={sort === 'duration' ? sortDir : false}>
                <TableSortLabel active={sort === 'duration'} direction={sort === 'duration' ? sortDir : 'desc'} onClick={() => handleSort('duration')}
                  sx={{ color: 'inherit !important', '& .MuiTableSortLabel-icon': { color: 'inherit !important' } }}>
                  {t('logAnalyzer.apiCalls.duration')}
                </TableSortLabel>
              </TableCell>
              <TableCell>{t('logAnalyzer.jobs.result')}</TableCell>
              {onJumpToLine && <TableCell>{t('logAnalyzer.customFields.line')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {jobs.map((job) => (
              <TableRow key={`${job.startLineNumber}-${job.sourceFile}`} hover>
                <TableCell><Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">{job.jobName}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.triggerName ?? '-'}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.thread}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.startTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem">{job.endTimestamp?.replace('T', ' ')}</Typography></TableCell>
                <TableCell><Chip size="small" label={formatDuration(job.durationMs)} color={job.durationMs >= 10000 ? 'warning' : 'default'} /></TableCell>
                <TableCell><Typography variant="body2" fontSize="0.8rem" color={job.result && job.result !== 'null' ? 'error.main' : 'text.secondary'}>{job.result}</Typography></TableCell>
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
