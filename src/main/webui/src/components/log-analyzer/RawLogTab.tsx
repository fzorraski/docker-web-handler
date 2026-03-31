import { useState, useEffect, useRef, useMemo, useCallback } from 'react'
import {
  Autocomplete, Box, Typography, TextField, Chip, IconButton, Tooltip,
  TablePagination, LinearProgress, InputAdornment,
  useTheme,
} from '@mui/material'
import { Search, ContentCopy, WrapText } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { List, type RowComponentProps } from 'react-window'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { getLogTheme } from '../../utils/logColors'
import type { LogLine, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE'
const TOGGLE_LEVELS: LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
const ROW_HEIGHT = 20
const VIEWER_HEIGHT = 500

interface RowCustomProps {
  lines: LogLine[]
  levelColor: (level: string | null) => string
  chipInactive: string
}

function VirtualRow({ index, style, lines, levelColor, chipInactive }: RowComponentProps<RowCustomProps>) {
  const line = lines[index]
  if (!line) return null
  return (
    <Box component="div" style={style} sx={{
      display: 'flex', px: 2,
      '&:hover': { bgcolor: 'rgba(255,255,255,0.03)' },
    }}>
      <Box sx={{ color: chipInactive, minWidth: 55, textAlign: 'right', pr: 1.5, userSelect: 'none', opacity: 0.5, flexShrink: 0, lineHeight: `${ROW_HEIGHT}px` }}>
        {line.lineNumber}
      </Box>
      <Box sx={{
        color: levelColor(line.level),
        whiteSpace: 'pre',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        flex: 1,
        lineHeight: `${ROW_HEIGHT}px`,
      }}>
        {line.message ?? ''}
      </Box>
    </Box>
  )
}

export function RawLogTab({ analysisId, initialThread, levelCounts: globalLevelCounts }: {
  analysisId: string; initialThread?: string; levelCounts?: Record<string, number>
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const lt = useMemo(() => getLogTheme(isDark), [isDark])

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(500)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [filterLevel, setFilterLevel] = useState('')
  const [filterThread, setFilterThread] = useState(initialThread ?? '')
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [wordWrap, setWordWrap] = useState(false)
  const [copySnackbar, setCopySnackbar] = useState(false)
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout>>(undefined)

  useEffect(() => {
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
  }, [analysisId])

  useEffect(() => () => { if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current) }, [])

  const effectiveRowsPerPage = wordWrap ? Math.min(rowsPerPage, 1000) : rowsPerPage

  const { data: lines, total, loading } = usePaginatedFetch<LogLine>(
    () => logService.getLines(analysisId, {
      thread: filterThread || undefined,
      level: filterLevel || undefined,
      search: debouncedSearch || undefined,
      page, size: effectiveRowsPerPage,
    }),
    [analysisId, filterThread, filterLevel, debouncedSearch, page, effectiveRowsPerPage],
  )

  const levelCounts = useMemo(() => {
    if (globalLevelCounts) {
      const g = globalLevelCounts
      return {
        ERROR: (g.ERROR ?? 0) + (g.FATAL ?? 0) + (g.SEVERE ?? 0),
        WARN: (g.WARN ?? 0) + (g.WARNING ?? 0),
        INFO: g.INFO ?? 0,
        DEBUG: g.DEBUG ?? 0,
        TRACE: g.TRACE ?? 0,
      }
    }
    const counts: Record<string, number> = {}
    for (const l of TOGGLE_LEVELS) counts[l] = 0
    for (const line of lines) {
      const lvl = line.level
      if (lvl === 'ERROR' || lvl === 'FATAL' || lvl === 'SEVERE') counts.ERROR++
      else if (lvl === 'WARN' || lvl === 'WARNING') counts.WARN++
      else if (lvl === 'INFO') counts.INFO++
      else if (lvl === 'DEBUG') counts.DEBUG++
      else if (lvl === 'TRACE') counts.TRACE++
    }
    return counts
  }, [globalLevelCounts, lines])

  const levelColor = useCallback((level: string | null): string => {
    switch (level) {
      case 'ERROR': case 'FATAL': case 'SEVERE': return lt.levelColors.ERROR
      case 'WARN': case 'WARNING': return lt.levelColors.WARN
      case 'INFO': return lt.levelColors.INFO
      case 'DEBUG': return lt.levelColors.DEBUG
      case 'TRACE': return lt.levelColors.TRACE
      default: return lt.levelColors.UNKNOWN
    }
  }, [lt])

  const handleCopyAll = async () => {
    const text = lines.map(l => l.message ?? '').join('\n')
    try {
      await navigator.clipboard.writeText(text)
      if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current)
      setCopySnackbar(true)
      copyTimeoutRef.current = setTimeout(() => setCopySnackbar(false), 1500)
    } catch { /* clipboard not available */ }
  }

  const rowProps = useMemo<RowCustomProps>(
    () => ({ lines, levelColor, chipInactive: lt.chipInactive }),
    [lines, levelColor, lt.chipInactive],
  )

  const wrapToggleColor = isDark ? '#4d96ff' : '#1565c0'

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 0 }} />}

      {/* Toolbar */}
      <Box sx={{
        bgcolor: lt.toolbarBg,
        px: 1.5, py: 0.75,
        display: 'flex', flexWrap: 'wrap', gap: 0.75, alignItems: 'center',
        borderBottom: `1px solid ${lt.toolbarBorder}`,
        borderRadius: '4px 4px 0 0',
      }}>
        <TextField
          size="small"
          placeholder={t('logAnalyzer.rawLog.search')}
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0) }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search sx={{ color: lt.searchPlaceholder, fontSize: 18 }} />
                </InputAdornment>
              ),
              sx: {
                bgcolor: lt.searchBg,
                color: lt.searchText,
                fontSize: '0.8rem',
                height: 32,
                '& input::placeholder': { color: lt.searchPlaceholder, opacity: 1 },
                '& .MuiOutlinedInput-notchedOutline': { borderColor: lt.searchBorder },
                '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: lt.searchBorderHover },
                '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: lt.searchBorderFocus },
              },
            },
          }}
          sx={{ minWidth: 160, maxWidth: 220 }}
        />

        <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
          {TOGGLE_LEVELS.map(level => {
            const color = lt.levelColors[level]
            const isFiltered = filterLevel === level || (filterLevel === 'WARNING' && level === 'WARN')
            const active = filterLevel === '' || isFiltered
            return (
              <Chip
                key={level}
                label={`${level} ${levelCounts[level]}`}
                size="small"
                onClick={() => { setFilterLevel(isFiltered ? '' : level); setPage(0) }}
                sx={{
                  bgcolor: isFiltered ? color + '22' : 'transparent',
                  color: active ? color : lt.chipInactive,
                  borderColor: isFiltered ? color + '88' : lt.chipBorderInactive,
                  fontSize: '0.7rem',
                  fontFamily: 'monospace',
                  height: 24,
                  cursor: 'pointer',
                  '&:hover': { bgcolor: color + '18' },
                }}
                variant="outlined"
              />
            )
          })}
        </Box>

        <Autocomplete
          size="small"
          sx={{ minWidth: 200, maxWidth: 280 }}
          options={threads}
          getOptionLabel={(th) => `${th.thread} (${th.lineCount.toLocaleString()})`}
          value={threads.find((th) => th.thread === filterThread) ?? null}
          onChange={(_, v) => { setFilterThread(v?.thread ?? ''); setPage(0) }}
          isOptionEqualToValue={(o, v) => o.thread === v.thread}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.rawLog.thread')}
            sx={{ '& .MuiInputBase-root': { height: 32, fontSize: '0.8rem' } }} />}
        />

        <Box sx={{ flex: 1 }} />

        <Tooltip title={wordWrap ? t('containers.logs.nowrapLines') : t('containers.logs.wrapLines')} arrow>
          <IconButton size="small" onClick={() => setWordWrap(!wordWrap)}
            sx={{ color: wordWrap ? wrapToggleColor : lt.iconColor }}>
            <WrapText sx={{ fontSize: 18 }} />
          </IconButton>
        </Tooltip>

        <Tooltip title={copySnackbar ? t('containers.logs.copied') : t('containers.logs.copyAll')} arrow>
          <IconButton size="small" onClick={handleCopyAll} sx={{ color: lt.iconColor }}>
            <ContentCopy sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>

        <Typography variant="caption" sx={{ color: lt.chipInactive, fontFamily: 'monospace', fontSize: '0.7rem' }}>
          {total.toLocaleString()} {t('logAnalyzer.common.lines')}
        </Typography>
      </Box>

      {/* Log viewer */}
      <Box sx={{
        fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace",
        fontSize: '0.8rem',
        bgcolor: lt.logViewerBg,
        borderRadius: '0 0 4px 4px',
      }}>
        {lines.length === 0 && !loading && (
          <Box sx={{ p: 3, textAlign: 'center', color: lt.emptyText }}>
            {t('containers.logs.noResults')}
          </Box>
        )}

        {lines.length > 0 && wordWrap ? (
          /* Wrap mode — plain divs, no virtualization */
          <Box sx={{ maxHeight: VIEWER_HEIGHT, overflowY: 'auto' }}>
            {lines.map((line) => (
              <Box
                key={`${line.sourceFile}-${line.lineNumber}`}
                sx={{
                  display: 'flex', px: 2, py: '1px',
                  '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' },
                }}
              >
                <Box sx={{ color: lt.chipInactive, minWidth: 55, textAlign: 'right', pr: 1.5, userSelect: 'none', opacity: 0.5, flexShrink: 0 }}>
                  {line.lineNumber}
                </Box>
                <Box sx={{
                  color: levelColor(line.level),
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  flex: 1,
                  lineHeight: '20px',
                }}>
                  {line.message ?? ''}
                </Box>
              </Box>
            ))}
          </Box>
        ) : lines.length > 0 ? (
          /* No-wrap mode — virtualized with react-window */
          <List
            rowComponent={VirtualRow}
            rowCount={lines.length}
            rowHeight={ROW_HEIGHT}
            rowProps={rowProps}
            overscanCount={20}
            style={{ backgroundColor: lt.logViewerBg, height: VIEWER_HEIGHT }}
          />
        ) : null}
      </Box>

      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        rowsPerPageOptions={[100, 500, 1000, 2000, 5000]}
        showFirstButton showLastButton />
    </Box>
  )
}
