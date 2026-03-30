import { useState, useEffect } from 'react'
import {
  Autocomplete, Box, Typography, TextField,
  TablePagination, LinearProgress, Stack,
  useTheme,
} from '@mui/material'
import { Search } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { getLogLevelColors } from '../../utils/logColors'
import type { LogLine, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function RawLogTab({ analysisId, initialThread }: { analysisId: string; initialThread?: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const levelColors = getLogLevelColors(isDark)

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(100)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [filterLevel, setFilterLevel] = useState('')
  const [filterThread, setFilterThread] = useState(initialThread ?? '')
  const [threads, setThreads] = useState<ThreadInfo[]>([])

  useEffect(() => {
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  const { data: lines, total, loading } = usePaginatedFetch<LogLine>(
    () => logService.getLines(analysisId, {
      thread: filterThread || undefined,
      level: filterLevel || undefined,
      search: debouncedSearch || undefined,
      page, size: rowsPerPage,
    }),
    [analysisId, filterThread, filterLevel, debouncedSearch, page, rowsPerPage],
  )

  const levelColor = (level: string | null) => {
    switch (level) {
      case 'ERROR': case 'FATAL': case 'SEVERE': return levelColors.ERROR
      case 'WARN': case 'WARNING': return levelColors.WARN
      case 'INFO': return levelColors.INFO
      case 'DEBUG': return levelColors.DEBUG
      case 'TRACE': return levelColors.TRACE
      default: return levelColors.UNKNOWN
    }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <TextField size="small" placeholder={t('logAnalyzer.rawLog.search')} value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0) }}
          slotProps={{ input: { startAdornment: <Search sx={{ mr: 1, color: 'text.secondary' }} /> } }}
          sx={{ minWidth: 250 }} />
        <Autocomplete
          size="small"
          sx={{ minWidth: 160 }}
          options={['INFO', 'WARN', 'WARNING', 'ERROR', 'DEBUG', 'TRACE']}
          value={filterLevel || null}
          onChange={(_, v) => { setFilterLevel(v ?? ''); setPage(0) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.rawLog.level')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 280 }}
          options={threads}
          getOptionLabel={(th) => `${th.thread} (${th.lineCount.toLocaleString()})`}
          value={threads.find((th) => th.thread === filterThread) ?? null}
          onChange={(_, v) => { setFilterThread(v?.thread ?? ''); setPage(0) }}
          isOptionEqualToValue={(o, v) => o.thread === v.thread}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.rawLog.thread')} />}
        />
      </Stack>

      <Box sx={{
        fontFamily: "'JetBrains Mono', monospace", fontSize: '0.78rem', lineHeight: 1.6,
        bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.03)',
        borderRadius: 1, p: 1, maxHeight: 600, overflowY: 'auto',
      }}>
        {lines.map((line) => (
          <Box key={`${line.sourceFile}-${line.lineNumber}`} sx={{ display: 'flex', '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' } }}>
            <Box sx={{ color: 'text.secondary', minWidth: 60, textAlign: 'right', pr: 1.5, userSelect: 'none', opacity: 0.5 }}>
              {line.lineNumber}
            </Box>
            <Box sx={{ color: levelColor(line.level), whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1 }}>
              {line.message ?? ''}
            </Box>
          </Box>
        ))}
      </Box>
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        rowsPerPageOptions={[50, 100, 200, 500]}
        showFirstButton showLastButton />
    </Box>
  )
}
