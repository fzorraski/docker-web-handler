import { useState, useEffect, useRef, useMemo, useCallback } from 'react'
import {
  Autocomplete, Box, Typography, TextField, Chip, IconButton, Tooltip,
  TablePagination, LinearProgress, InputAdornment, Dialog,
  useTheme,
} from '@mui/material'
import { Search, ContentCopy, WrapText, KeyboardArrowUp, KeyboardArrowDown, MyLocation, ClearAll, HighlightOff, Fullscreen, FullscreenExit, AccessTime } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { List, useListRef, type RowComponentProps } from 'react-window'
import { usePaginatedFetch } from '../../hooks/usePaginatedFetch'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { getLogTheme } from '../../utils/logColors'
import type { LogLine, ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE'
const TOGGLE_LEVELS: LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
const ROW_HEIGHT = 20
const VIEWER_HEIGHT = 500

function getLineBg(lineNumber: number, highlightLine: number | null, flashLine: number | null, markedLines: Set<number>, isDark: boolean, highlightRange?: { from: number; to: number } | null): string | undefined {
  if (highlightRange && lineNumber >= highlightRange.from && lineNumber <= highlightRange.to) {
    return isDark ? 'rgba(156, 39, 176, 0.25)' : 'rgba(156, 39, 176, 0.15)'
  }
  if (lineNumber === highlightLine) return isDark ? 'rgba(255, 109, 0, 0.35)' : 'rgba(255, 109, 0, 0.25)'
  if (lineNumber === flashLine) return isDark ? 'rgba(0, 188, 212, 0.30)' : 'rgba(0, 150, 136, 0.25)'
  if (markedLines.has(lineNumber)) return isDark ? 'rgba(0, 188, 212, 0.10)' : 'rgba(0, 150, 136, 0.08)'
  return undefined
}

function formatTime(ts: string | null): string {
  if (!ts) return ''
  // ts is ISO-like: "2026-03-30T10:00:00.49" or "2026-03-30 10:00:00,123"
  const tIdx = ts.indexOf('T')
  const spIdx = ts.indexOf(' ')
  const sep = tIdx >= 0 ? tIdx + 1 : spIdx >= 0 ? spIdx + 1 : 0
  let time = ts.substring(sep)
  // Pad fractional seconds to 3 digits (Java trims trailing zeros: .49 → .490)
  const dotIdx = time.indexOf('.')
  const commaIdx = time.indexOf(',')
  const fracIdx = dotIdx >= 0 ? dotIdx : commaIdx
  if (fracIdx >= 0) {
    const frac = time.substring(fracIdx + 1)
    time = time.substring(0, fracIdx + 1) + frac.padEnd(3, '0')
  }
  return time
}

interface RowCustomProps {
  getLine: (index: number) => LogLine | undefined
  dataVersion: object // new ref each time lines change — forces react-window row re-render
  levelColor: (level: string | null) => string
  chipInactive: string
  highlightLine: number | null
  flashLine: number | null
  markedLines: Set<number>
  onToggleMark: (lineNumber: number) => void
  isDark: boolean
  showTimestamp: boolean
  highlightRange?: { from: number; to: number } | null
}

const ROW_LINE_HEIGHT = { lineHeight: `${ROW_HEIGHT}px` } as const
const ROW_MESSAGE_SX = { whiteSpace: 'pre', overflow: 'hidden', textOverflow: 'ellipsis', flex: 1, ...ROW_LINE_HEIGHT } as const
const ROW_TIMESTAMP_SX = { minWidth: 100, pr: 1, flexShrink: 0, opacity: 0.7, ...ROW_LINE_HEIGHT } as const
const ROW_HOVER_DARK = { bgcolor: 'rgba(255,255,255,0.03)' } as const
const ROW_HOVER_LIGHT = { bgcolor: 'rgba(0,0,0,0.02)' } as const

function VirtualRow({ index, style, getLine, levelColor, chipInactive, highlightLine, flashLine, markedLines, onToggleMark, isDark, showTimestamp, highlightRange }: RowComponentProps<RowCustomProps>) {
  const line = getLine(index)
  if (!line) return null
  const isMarked = markedLines.has(line.lineNumber)
  const bg = getLineBg(line.lineNumber, highlightLine, flashLine, markedLines, isDark, highlightRange)
  const markColor = isDark ? '#00BCD4' : '#009688'
  return (
    <Box component="div" style={style} data-line={line.lineNumber} sx={{
      display: 'flex', px: 2,
      bgcolor: bg,
      borderLeft: isMarked ? '3px solid' : '3px solid transparent',
      borderColor: isMarked ? markColor : 'transparent',
      '&:hover': isDark ? ROW_HOVER_DARK : ROW_HOVER_LIGHT,
    }}>
      <Box
        sx={{
          minWidth: 55, textAlign: 'right', pr: 1.5, userSelect: 'none', flexShrink: 0,
          cursor: 'pointer',
          color: isMarked ? markColor : chipInactive,
          opacity: isMarked ? 1 : 0.5,
          '&:hover': { opacity: 1, color: markColor },
          ...ROW_LINE_HEIGHT,
        }}
        onClick={() => onToggleMark(line.lineNumber)}
      >
        {line.lineNumber}
      </Box>
      {showTimestamp && (
        <Box sx={{ color: chipInactive, ...ROW_TIMESTAMP_SX }}>
          {formatTime(line.timestamp)}
        </Box>
      )}
      <Box sx={{ color: levelColor(line.level), ...ROW_MESSAGE_SX }}>
        {line.message ?? ''}
      </Box>
    </Box>
  )
}

export function RawLogTab({ analysisId, initialThread, initialLevel, levelCounts: globalLevelCounts, jumpToLine, onJumpComplete, highlightRange, onRangeComplete }: {
  analysisId: string; initialThread?: string; initialLevel?: string | null; levelCounts?: Record<string, number>
  jumpToLine?: number | null; onJumpComplete?: () => void
  highlightRange?: { from: number; to: number } | null; onRangeComplete?: () => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const lt = useMemo(() => getLogTheme(isDark), [isDark])

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(500)
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [activeSearch, setActiveSearch] = useState('') // actual value sent to API
  const [filterLevel, setFilterLevel] = useState(initialLevel ?? '')
  useEffect(() => { if (initialLevel) { setFilterLevel(initialLevel); setPage(0) } }, [initialLevel])
  const [filterThread, setFilterThread] = useState(initialThread ?? '')
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [wordWrap, setWordWrap] = useState(false)
  const [showTimestamp, setShowTimestamp] = useState(false)
  const [copySnackbar, setCopySnackbar] = useState(false)
  const [highlightLine, setHighlightLine] = useState<number | null>(null)
  const [markedLines, setMarkedLines] = useState<Set<number>>(new Set())
  const [scrollTarget, setScrollTarget] = useState<number | null>(null)
  const [scrollGen, setScrollGen] = useState(0)
  const [flashLine, setFlashLine] = useState<number | null>(null)
  const [fullscreen, setFullscreen] = useState(false)
  const [fullscreenHeight, setFullscreenHeight] = useState(VIEWER_HEIGHT)
  const firstVisibleIndexRef = useRef(0)
  const scrollAlignRef = useRef<'center' | 'start'>('center')
  const wrapScrollRafRef = useRef(0)
  const prevPageRef = useRef(page)
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const linesRef = useRef<LogLine[]>([])
  const isFirstMount = useRef(true)
  const listRef = useListRef(null)
  const wrapContainerRef = useRef<HTMLDivElement>(null)
  const toolbarRef = useRef<HTMLDivElement>(null)
  const paginationRef = useRef<HTMLDivElement>(null)

  // Sync debounced search to active search (normal typing flow)
  useEffect(() => { setActiveSearch(debouncedSearch) }, [debouncedSearch])

  useEffect(() => {
    if (isFirstMount.current) {
      isFirstMount.current = false
    } else {
      setPage(0)
      setSearch('')
      setActiveSearch('')
      setFilterLevel(initialLevel ?? '')
      setFilterThread(initialThread ?? '')
      setMarkedLines(new Set())
      setHighlightLine(null)
      setFlashLine(null)
      setScrollTarget(null)
    }
    logService.getThreads(analysisId).then(setThreads).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [analysisId])

  useEffect(() => () => {
    if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current)
    cancelAnimationFrame(wrapScrollRafRef.current)
  }, [])

  // Measure available viewer height in fullscreen from toolbar + pagination
  useEffect(() => {
    if (!fullscreen) return
    const measure = () => {
      const tbH = toolbarRef.current?.offsetHeight ?? 0
      const pgH = paginationRef.current?.offsetHeight ?? 0
      const h = window.innerHeight - tbH - pgH - 4 // 4px for LinearProgress
      if (h > 0) setFullscreenHeight(h)
    }
    // Measure after dialog renders; retry once if refs aren't mounted yet
    requestAnimationFrame(() => { measure(); setTimeout(measure, 50) })
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [fullscreen])

  // Handle jump to line — clear filters immediately, go to correct page
  useEffect(() => {
    if (jumpToLine == null) return
    setFilterLevel('')
    setFilterThread('')
    setSearch('')
    setActiveSearch('') // bypass debounce
    const rpp = wordWrap ? Math.min(rowsPerPage, 1000) : rowsPerPage
    setPage(Math.floor((jumpToLine - 1) / rpp))
    setHighlightLine(jumpToLine)
    setScrollTarget(jumpToLine)
    setScrollGen(g => g + 1)
  }, [jumpToLine])

  // Handle highlight range — clear filters and navigate to range start
  useEffect(() => {
    if (highlightRange == null) return
    setFilterLevel('')
    setFilterThread('')
    setSearch('')
    setActiveSearch('')
    const rpp = wordWrap ? Math.min(rowsPerPage, 1000) : rowsPerPage
    setPage(Math.floor((highlightRange.from - 1) / rpp))
    setScrollTarget(highlightRange.from)
    setScrollGen(g => g + 1)
  }, [highlightRange])

  const effectiveRowsPerPage = wordWrap ? Math.min(rowsPerPage, 1000) : rowsPerPage

  const { data: lines, total, loading } = usePaginatedFetch<LogLine>(
    (signal) => logService.getLines(analysisId, {
      thread: filterThread || undefined,
      level: filterLevel || undefined,
      search: activeSearch || undefined,
      page, size: effectiveRowsPerPage, signal,
    }),
    [analysisId, filterThread, filterLevel, activeSearch, page, effectiveRowsPerPage],
  )

  linesRef.current = lines
  const getLine = useCallback((i: number) => linesRef.current[i], [])
  // New ref each time lines changes — forces react-window row re-render since getLine is stable
  const dataVersion = useMemo(() => ({}), [lines])

  // Scroll to top when page changes (but not when a scrollTarget is pending)
  useEffect(() => {
    if (page === prevPageRef.current || lines.length === 0) {
      prevPageRef.current = page
      return
    }
    prevPageRef.current = page
    if (scrollTarget != null) return // a jump/fullscreen toggle is pending — let it handle scroll
    requestAnimationFrame(() => {
      if (!wordWrap && listRef.current) {
        listRef.current.scrollToRow({ index: 0, align: 'start' })
      } else if (wrapContainerRef.current) {
        wrapContainerRef.current.scrollTop = 0
      }
    })
  }, [page, lines, scrollTarget, wordWrap])

  // Scroll to target line after data loads (works for both highlight and bookmark navigation)
  const activeScrollTarget = scrollTarget
  useEffect(() => {
    const curLines = linesRef.current
    if (activeScrollTarget == null || curLines.length === 0) return
    const idx = curLines.findIndex(l => l.lineNumber === activeScrollTarget)
    if (idx === -1) return
    setScrollTarget(null)
    const align = scrollAlignRef.current
    scrollAlignRef.current = 'center' // reset to default
    requestAnimationFrame(() => {
      if (!wordWrap && listRef.current) {
        listRef.current.scrollToRow({ index: idx, align })
      } else if (wrapContainerRef.current) {
        const el = wrapContainerRef.current.querySelector(`[data-line="${activeScrollTarget}"]`) as HTMLElement | null
        if (el) {
          const container = wrapContainerRef.current
          const elTop = el.offsetTop - container.offsetTop
          container.scrollTop = align === 'start' ? elTop : elTop - container.clientHeight / 2
        }
      }
      onJumpComplete?.()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeScrollTarget, scrollGen, lines, wordWrap])

  const levelCounts = useMemo(() => {
    if (globalLevelCounts) {
      const g = globalLevelCounts
      const counts: Record<string, number> = {
        ERROR: g.ERROR ?? 0,
        WARN: (g.WARN ?? 0) + (g.WARNING ?? 0),
        INFO: g.INFO ?? 0,
        DEBUG: g.DEBUG ?? 0,
        TRACE: g.TRACE ?? 0,
      }
      if ((g.SEVERE ?? 0) > 0) counts.SEVERE = g.SEVERE ?? 0
      if ((g.FATAL ?? 0) > 0) counts.FATAL = g.FATAL ?? 0
      return counts
    }
    const counts: Record<string, number> = {}
    for (const l of TOGGLE_LEVELS) counts[l] = 0
    for (const line of lines) {
      const lvl = line.level
      if (lvl === 'ERROR') counts.ERROR++
      else if (lvl === 'SEVERE') counts.SEVERE = (counts.SEVERE ?? 0) + 1
      else if (lvl === 'FATAL') counts.FATAL = (counts.FATAL ?? 0) + 1
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

  const handleCopyAll = useCallback(async () => {
    const text = linesRef.current.map(l => l.message ?? '').join('\n')
    try {
      await navigator.clipboard.writeText(text)
      if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current)
      setCopySnackbar(true)
      copyTimeoutRef.current = setTimeout(() => setCopySnackbar(false), 1500)
    } catch { /* clipboard not available */ }
  }, [])

  const toggleMark = useCallback((lineNumber: number) => {
    setMarkedLines(prev => {
      const next = new Set(prev)
      if (next.has(lineNumber)) next.delete(lineNumber)
      else next.add(lineNumber)
      return next
    })
  }, [])

  const sortedMarks = useMemo(() => Array.from(markedLines).sort((a, b) => a - b), [markedLines])
  const [currentMarkIdx, setCurrentMarkIdx] = useState(-1)

  // Clamp currentMarkIdx when marks are removed
  useEffect(() => {
    if (currentMarkIdx >= sortedMarks.length) setCurrentMarkIdx(sortedMarks.length - 1)
  }, [sortedMarks.length, currentMarkIdx])

  const scrollToLine = useCallback((targetLine: number) => {
    const rpp = wordWrap ? Math.min(rowsPerPage, 1000) : rowsPerPage
    const targetPage = Math.floor((targetLine - 1) / rpp)
    setPage(targetPage)
    setScrollTarget(targetLine)
    setScrollGen(g => g + 1)
    setFlashLine(targetLine)
  }, [wordWrap, rowsPerPage])

  const jumpToNextMark = useCallback(() => {
    if (sortedMarks.length === 0) return
    const nextIdx = currentMarkIdx + 1 >= sortedMarks.length ? 0 : currentMarkIdx + 1
    setCurrentMarkIdx(nextIdx)
    scrollToLine(sortedMarks[nextIdx])
  }, [sortedMarks, currentMarkIdx, scrollToLine])

  const jumpToPrevMark = useCallback(() => {
    if (sortedMarks.length === 0) return
    const prevIdx = currentMarkIdx - 1 < 0 ? sortedMarks.length - 1 : currentMarkIdx - 1
    setCurrentMarkIdx(prevIdx)
    scrollToLine(sortedMarks[prevIdx])
  }, [sortedMarks, currentMarkIdx, scrollToLine])

  const jumpToHighlight = useCallback(() => {
    if (highlightLine == null) return
    scrollToLine(highlightLine)
  }, [highlightLine, scrollToLine])

  const handleRowsRendered = useCallback((visibleRows: { startIndex: number; stopIndex: number }) => {
    firstVisibleIndexRef.current = visibleRows.startIndex
  }, [])

  const handleWrapScroll = useCallback(() => {
    cancelAnimationFrame(wrapScrollRafRef.current)
    wrapScrollRafRef.current = requestAnimationFrame(() => {
      const container = wrapContainerRef.current
      const curLines = linesRef.current
      if (!container || curLines.length === 0) return
      const children = container.children
      for (let i = 0; i < children.length; i++) {
        const child = children[i] as HTMLElement
        if (child.offsetTop + child.offsetHeight > container.scrollTop) {
          const lineNum = Number(child.dataset.line)
          if (lineNum > 0) {
            const idx = curLines.findIndex(l => l.lineNumber === lineNum)
            if (idx >= 0) firstVisibleIndexRef.current = idx
          }
          break
        }
      }
    })
  }, [])

  const toggleFullscreen = useCallback(() => {
    const visibleLine = linesRef.current[firstVisibleIndexRef.current]?.lineNumber
    setFullscreen(f => !f)
    if (visibleLine != null) {
      scrollAlignRef.current = 'start'
      setScrollTarget(visibleLine)
      setScrollGen(g => g + 1)
    }
  }, [])

  const rowProps = useMemo<RowCustomProps>(
    () => ({ getLine, dataVersion, levelColor, chipInactive: lt.chipInactive, highlightLine, flashLine, markedLines, onToggleMark: toggleMark, isDark, showTimestamp, highlightRange }),
    [getLine, dataVersion, levelColor, lt.chipInactive, highlightLine, flashLine, markedLines, toggleMark, isDark, showTimestamp, highlightRange],
  )

  const wrapToggleColor = isDark ? '#4d96ff' : '#1565c0'

  const viewerHeight = fullscreen ? fullscreenHeight : VIEWER_HEIGHT

  const toolbarContent = (
    <Box ref={toolbarRef} sx={{
      bgcolor: lt.toolbarBg,
      px: 1.5, py: 0.75,
      display: 'flex', flexWrap: 'wrap', gap: 0.75, alignItems: 'center',
      borderBottom: `1px solid ${lt.toolbarBorder}`,
      borderRadius: fullscreen ? 0 : '4px 4px 0 0',
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
        {Object.entries(levelCounts).filter(([, c]) => c > 0).map(([level]) => {
          const color = (lt.levelColors as Record<string, string>)[level] ?? lt.levelColors.ERROR
          const isFiltered = filterLevel === level
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

      {/* Bookmark navigation */}
      {sortedMarks.length > 0 && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25, borderLeft: `1px solid ${lt.toolbarBorder}`, pl: 1 }}>
          <Tooltip title={t('logAnalyzer.rawLog.prevMark')} arrow>
            <IconButton size="small" onClick={jumpToPrevMark} sx={{ color: '#00BCD4' }}>
              <KeyboardArrowUp sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Typography variant="caption" sx={{ color: '#00BCD4', fontFamily: 'monospace', fontSize: '0.7rem', minWidth: 30, textAlign: 'center' }}>
            {currentMarkIdx >= 0 ? `${currentMarkIdx + 1}/${sortedMarks.length}` : sortedMarks.length}
          </Typography>
          <Tooltip title={t('logAnalyzer.rawLog.nextMark')} arrow>
            <IconButton size="small" onClick={jumpToNextMark} sx={{ color: '#00BCD4' }}>
              <KeyboardArrowDown sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Tooltip title={t('logAnalyzer.rawLog.clearMarks')} arrow>
            <IconButton size="small" onClick={() => { setMarkedLines(new Set()); setCurrentMarkIdx(-1); setFlashLine(null) }} sx={{ color: lt.iconColor, ml: 0.25 }}>
              <ClearAll sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
        </Box>
      )}

      {/* Jump to highlighted line */}
      {highlightLine != null && (
        <Tooltip title={`${t('logAnalyzer.rawLog.goToHighlight')} L${highlightLine}`} arrow>
          <IconButton size="small" onClick={jumpToHighlight} sx={{ color: '#FF6D00' }}>
            <MyLocation sx={{ fontSize: 18 }} />
          </IconButton>
        </Tooltip>
      )}

      {/* Clear all highlights */}
      {(highlightLine != null || (highlightRange && highlightRange.from > 0)) && (
        <>
        <Tooltip title={t('logAnalyzer.rawLog.copyHighlighted')} arrow>
          <IconButton size="small" onClick={() => {
            const from = highlightRange?.from ?? highlightLine ?? 0
            const to = highlightRange?.to ?? highlightLine ?? 0
            if (from <= 0) return
            const text = linesRef.current.filter(l => l.lineNumber >= from && l.lineNumber <= to)
              .map(l => `${l.lineNumber}\t${l.message ?? ''}`).join('\n')
            navigator.clipboard.writeText(text)
          }} sx={{ color: '#9C27B0' }}>
            <ContentCopy sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title={t('logAnalyzer.rawLog.clearHighlights')} arrow>
          <IconButton size="small" onClick={() => { setHighlightLine(null); setFlashLine(null); onJumpComplete?.(); onRangeComplete?.() }} sx={{ color: lt.iconColor }}>
            <HighlightOff sx={{ fontSize: 18 }} />
          </IconButton>
        </Tooltip>
        </>
      )}

      <Box sx={{ flex: 1 }} />

      <Tooltip title={wordWrap ? t('containers.logs.nowrapLines') : t('containers.logs.wrapLines')} arrow>
        <IconButton size="small" onClick={() => setWordWrap(!wordWrap)}
          sx={{ color: wordWrap ? wrapToggleColor : lt.iconColor }}>
          <WrapText sx={{ fontSize: 18 }} />
        </IconButton>
      </Tooltip>

      <Tooltip title={showTimestamp ? t('logAnalyzer.rawLog.hideTimestamp') : t('logAnalyzer.rawLog.showTimestamp')} arrow>
        <IconButton size="small" onClick={() => setShowTimestamp(v => !v)}
          sx={{ color: showTimestamp ? wrapToggleColor : lt.iconColor }}>
          <AccessTime sx={{ fontSize: 18 }} />
        </IconButton>
      </Tooltip>

      <Tooltip title={copySnackbar ? t('containers.logs.copied') : t('containers.logs.copyAll')} arrow>
        <IconButton size="small" onClick={handleCopyAll} sx={{ color: lt.iconColor }}>
          <ContentCopy sx={{ fontSize: 16 }} />
        </IconButton>
      </Tooltip>

      <Tooltip title={fullscreen ? t('logAnalyzer.rawLog.exitFullscreen') : t('logAnalyzer.rawLog.fullscreen')} arrow>
        <IconButton size="small" onClick={toggleFullscreen} sx={{ color: lt.iconColor }}>
          {fullscreen ? <FullscreenExit sx={{ fontSize: 18 }} /> : <Fullscreen sx={{ fontSize: 18 }} />}
        </IconButton>
      </Tooltip>

      <Typography variant="caption" sx={{ color: lt.chipInactive, fontFamily: 'monospace', fontSize: '0.7rem' }}>
        {total.toLocaleString()} {t('logAnalyzer.common.lines')}
      </Typography>
    </Box>
  )

  const logViewerContent = (
    <Box sx={{
      fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace",
      fontSize: '0.8rem',
      bgcolor: lt.logViewerBg,
      borderRadius: fullscreen ? 0 : '0 0 4px 4px',
    }}>
      {lines.length === 0 && !loading && (
        <Box sx={{ p: 3, textAlign: 'center', color: lt.emptyText }}>
          {t('containers.logs.noResults')}
        </Box>
      )}

      {lines.length > 0 && wordWrap ? (
        /* Wrap mode — plain divs, no virtualization */
        <Box ref={wrapContainerRef} onScroll={handleWrapScroll} sx={{ maxHeight: viewerHeight, overflowY: 'auto' }}>
          {lines.map((line) => {
            const isMarked = markedLines.has(line.lineNumber)
            const bg = getLineBg(line.lineNumber, highlightLine, flashLine, markedLines, isDark, highlightRange)
            return (
              <Box
                key={`${line.sourceFile}-${line.lineNumber}`}
                data-line={line.lineNumber}
                sx={{
                  display: 'flex', px: 2, py: '1px',
                  bgcolor: bg,
                  transition: 'background-color 0.5s',
                  borderLeft: isMarked ? '3px solid' : '3px solid transparent',
                  borderColor: isMarked ? (isDark ? '#00BCD4' : '#009688') : 'transparent',
                  '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' },
                }}
              >
                <Box
                  sx={{
                    minWidth: 55, textAlign: 'right', pr: 1.5, userSelect: 'none', flexShrink: 0,
                    cursor: 'pointer',
                    color: isMarked ? (isDark ? '#00BCD4' : '#009688') : lt.chipInactive,
                    opacity: isMarked ? 1 : 0.5,
                    '&:hover': { opacity: 1, color: isDark ? '#00BCD4' : '#009688' },
                  }}
                  onClick={() => toggleMark(line.lineNumber)}
                >
                  {line.lineNumber}
                </Box>
                {showTimestamp && (
                  <Box sx={{
                    minWidth: 100, pr: 1, flexShrink: 0, lineHeight: '20px',
                    color: lt.chipInactive, opacity: 0.7,
                  }}>
                    {formatTime(line.timestamp)}
                  </Box>
                )}
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
            );
          })}
        </Box>
      ) : lines.length > 0 ? (
        /* No-wrap mode — virtualized with react-window */
        <List
          listRef={listRef}
          rowComponent={VirtualRow}
          rowCount={lines.length}
          rowHeight={ROW_HEIGHT}
          rowProps={rowProps}
          overscanCount={20}
          onRowsRendered={handleRowsRendered}
          style={{ backgroundColor: lt.logViewerBg, height: viewerHeight }}
        />
      ) : null}
    </Box>
  )

  const paginationContent = (
    <Box ref={paginationRef}>
      <TablePagination component="div" count={total} page={page} onPageChange={(_, p) => setPage(p)}
        rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
        rowsPerPageOptions={[100, 500, 1000, 2000, 5000]}
        showFirstButton showLastButton />
    </Box>
  )

  if (fullscreen) {
    return (
      <Dialog fullScreen open onClose={toggleFullscreen} PaperProps={{ sx: { bgcolor: lt.logViewerBg } }}>
        {loading && <LinearProgress />}
        {toolbarContent}
        {logViewerContent}
        {paginationContent}
      </Dialog>
    )
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 0 }} />}
      {toolbarContent}
      {logViewerContent}
      {paginationContent}
    </Box>
  )
}
