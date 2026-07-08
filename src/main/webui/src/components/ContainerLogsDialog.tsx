import { useState, useEffect, useRef, useCallback, useMemo, useDeferredValue, memo } from 'react'
import { copyToClipboard } from '../utils/clipboard'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Box,
  Typography,
  Chip,
  IconButton,
  Tooltip,
  TextField,
  InputAdornment,
  Snackbar,
  Alert,
  useTheme,
} from '@mui/material'
import {
  Terminal,
  KeyboardArrowDown,
  Search,
  KeyboardArrowUp,
  WrapText,
  ContentCopy,
  BugReport,
  Pause,
  PlayArrow,
  ClearAll,
  Analytics,
} from '@mui/icons-material'
import { useNavigate } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import { P } from '../utils/permissions'
import { isLogAnalyzerEnabled, analyzeContainerLogs } from '../services/logAnalyzerService'
import { List, useListRef, type RowComponentProps } from 'react-window'
import { useTranslation } from 'react-i18next'
import { streamContainerLogs } from '../services/sseService'
import type { ContainerEvent } from '../services/sseService'
import { parseLogLevel, stripAnsi, type LogLevel } from '../utils/logLevelParser'
import { getLogTheme, type LogTheme } from '../utils/logColors'
import useFullScreenDialog from '../hooks/useFullScreenDialog'
import FullscreenToggleButton from './FullscreenToggleButton'

interface LogEntry {
  stream: string
  message: string
  level: LogLevel
  isStackTrace: boolean
  isExceptionStart: boolean
}

interface Props {
  open: boolean
  containerId: string
  containerName: string
  onClose: () => void
}

interface RowCustomProps {
  logs: LogEntry[]
  search: string
  highlightedIndex: number
  theme: LogTheme
}

const MAX_LOG_LINES = 5000
const TRIM_THRESHOLD = MAX_LOG_LINES + 500
const FLUSH_INTERVAL_MS = 100
const ROW_HEIGHT = 20
const CONTAINER_HEIGHT = 500
const TOGGLE_LEVELS: LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG']

const shimmerSx = {
  background: 'linear-gradient(90deg, #FF6D00, #FFC107, #FF6D00)',
  backgroundSize: '200% 100%',
  animation: 'shimmer 1.5s ease-in-out infinite',
  color: '#fff !important',
  '@keyframes shimmer': {
    '0%': { backgroundPosition: '200% 0' },
    '100%': { backgroundPosition: '-200% 0' },
  },
  '&.Mui-disabled': { color: '#fff !important' },
}

const sparkleSpinSx = {
  animation: 'sparkle-spin 1.5s ease-in-out infinite',
  '@keyframes sparkle-spin': {
    '0%': { transform: 'rotate(0deg) scale(1)', filter: 'drop-shadow(0 0 2px #FF6D00)' },
    '25%': { transform: 'rotate(90deg) scale(1.2)', filter: 'drop-shadow(0 0 6px #FFC107)' },
    '50%': { transform: 'rotate(180deg) scale(1)', filter: 'drop-shadow(0 0 8px #FF6D00)' },
    '75%': { transform: 'rotate(270deg) scale(1.2)', filter: 'drop-shadow(0 0 6px #FFC107)' },
    '100%': { transform: 'rotate(360deg) scale(1)', filter: 'drop-shadow(0 0 2px #FF6D00)' },
  },
}

const HighlightedText = memo(function HighlightedText({ text, search, markColor }: { text: string; search: string; markColor: string }) {
  if (!search) return <>{text}</>

  const lowerText = text.toLowerCase()
  const lowerSearch = search.toLowerCase()
  const parts: (string | React.ReactElement)[] = []
  let lastIndex = 0
  let idx = lowerText.indexOf(lowerSearch, lastIndex)
  let key = 0

  while (idx !== -1) {
    if (idx > lastIndex) parts.push(text.slice(lastIndex, idx))
    parts.push(
      <mark key={key++} style={{ backgroundColor: markColor, color: '#000', borderRadius: 2, padding: '0 1px' }}>
        {text.slice(idx, idx + search.length)}
      </mark>
    )
    lastIndex = idx + search.length
    idx = lowerText.indexOf(lowerSearch, lastIndex)
  }
  if (lastIndex < text.length) parts.push(text.slice(lastIndex))

  return <>{parts}</>
})

function VirtualRow({ index, style, logs, search, highlightedIndex, theme: lt }: RowComponentProps<RowCustomProps>) {
  const log = logs[index]
  if (!log) return null
  const isHighlighted = index === highlightedIndex
  const isException = log.isStackTrace || log.isExceptionStart

  return (
    <Box
      component="div"
      style={style}
      sx={{
        color: log.isStackTrace ? lt.stackTraceColor : log.isExceptionStart ? lt.excColor : lt.levelColors[log.level],
        fontStyle: log.isStackTrace ? 'italic' : 'normal',
        fontWeight: log.isExceptionStart ? 600 : 'normal',
        bgcolor: isHighlighted ? lt.excHighlightBg : isException ? lt.excSubtleBg : 'transparent',
        whiteSpace: 'pre',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        px: 2,
        fontFamily: '"Cascadia Code", "Fira Code", "JetBrains Mono", monospace',
        fontSize: '0.8rem',
        lineHeight: `${ROW_HEIGHT}px`,
      }}
    >
      <HighlightedText text={log.message} search={search} markColor={lt.highlightMark} />
    </Box>
  )
}

export default function ContainerLogsDialog({ open, containerId, containerName, onClose }: Props) {
  const { t } = useTranslation()
  const { hasPermission } = useAuth()
  const canAnalyze = hasPermission(P.LOGS_ANALYZE)
  const muiTheme = useTheme()
  const isDark = muiTheme.palette.mode === 'dark'
  const lt = useMemo(() => getLogTheme(isDark), [isDark])

  const [logs, setLogs] = useState<LogEntry[]>([])
  const [connected, setConnected] = useState(false)
  const [autoScroll, setAutoScroll] = useState(true)
  const cleanupRef = useRef<(() => void) | null>(null)

  const [searchTerm, setSearchTerm] = useState('')
  const deferredSearch = useDeferredValue(searchTerm)
  const [visibleLevels, setVisibleLevels] = useState<Set<LogLevel>>(
    () => new Set(['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE', 'UNKNOWN'])
  )
  const [showExceptions, setShowExceptions] = useState(true)
  const [wordWrap, setWordWrap] = useState(false)
  const { fullScreen, toggleFullScreen, resetFullScreen, dialogProps, contentSx, viewerSx } = useFullScreenDialog()
  const [currentErrorIdx, setCurrentErrorIdx] = useState(-1)
  const [currentExcIdx, setCurrentExcIdx] = useState(-1)
  const [copySnackbar, setCopySnackbar] = useState(false)
  const [analyzerEnabled, setAnalyzerEnabled] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const navigate = useNavigate()
  const [paused, setPaused] = useState(false)
  const [pausedBufferCount, setPausedBufferCount] = useState(0)
  const [streamError, setStreamError] = useState('')
  const pausedRef = useRef(false)

  const listRef = useListRef(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const displayedCountRef = useRef(0)
  const pendingLogsRef = useRef<LogEntry[]>([])
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Flush buffered log entries into state (batched to reduce re-renders and GC pressure)
  const flushLogs = useCallback(() => {
    flushTimerRef.current = null
    const pending = pendingLogsRef.current
    if (pending.length === 0) return
    pendingLogsRef.current = []
    setLogs(prev => {
      const combined = prev.concat(pending)
      return combined.length > TRIM_THRESHOLD ? combined.slice(-MAX_LOG_LINES) : combined
    })
  }, [])

  useEffect(() => {
    // probe lazily on open: the dialog is always mounted, and the status endpoint needs LOGS_VIEW
    if (!open || !hasPermission(P.LOGS_VIEW)) return
    isLogAnalyzerEnabled().then(setAnalyzerEnabled).catch(() => setAnalyzerEnabled(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  useEffect(() => {
    if (!open || !containerId) return

    setLogs([])
    pendingLogsRef.current = []
    setConnected(true)
    setStreamError('')
    setAutoScroll(true)
    setSearchTerm('')
    setCurrentErrorIdx(-1)
    setCurrentExcIdx(-1)

    cleanupRef.current = streamContainerLogs(
      containerId,
      (event: ContainerEvent) => {
        if (event.type === 'INFO' || event.type === 'PROGRESS') {
          const message = stripAnsi(event.message)
          const { level, isStackTrace, isExceptionStart } = parseLogLevel(message, event.step)
          const pending = pendingLogsRef.current
          pending.push({ stream: event.step, message, level, isStackTrace, isExceptionStart })
          if (pausedRef.current) {
            // Cap buffer while paused to prevent unbounded memory growth
            if (pending.length > TRIM_THRESHOLD) {
              pendingLogsRef.current = pending.slice(-MAX_LOG_LINES)
            }
          } else if (flushTimerRef.current === null) {
            flushTimerRef.current = setTimeout(flushLogs, FLUSH_INTERVAL_MS)
          }
        }
      },
      () => { flushLogs(); setConnected(false) },
      (msg) => { flushLogs(); setConnected(false); setStreamError(msg) },
    )

    return () => {
      cleanupRef.current?.()
      cleanupRef.current = null
      if (flushTimerRef.current !== null) {
        clearTimeout(flushTimerRef.current)
        flushTimerRef.current = null
      }
      pendingLogsRef.current = []
    }
  }, [open, containerId])

  // Filtered logs by level
  const levelFilteredLogs = useMemo(
    () => logs.filter(log => visibleLevels.has(log.level)),
    [logs, visibleLevels]
  )

  // Filtered logs by exceptions
  const exceptionFilteredLogs = useMemo(() => {
    if (showExceptions) return levelFilteredLogs
    return levelFilteredLogs.filter(log => !log.isStackTrace && !log.isExceptionStart)
  }, [levelFilteredLogs, showExceptions])

  // Filtered logs by search
  const displayedLogs = useMemo(() => {
    if (!deferredSearch) return exceptionFilteredLogs
    const lower = deferredSearch.toLowerCase()
    return exceptionFilteredLogs.filter(log => log.message.toLowerCase().includes(lower))
  }, [exceptionFilteredLogs, deferredSearch])

  displayedCountRef.current = displayedLogs.length

  // Level counts (from all logs, not filtered)
  const levelCounts = useMemo(() => {
    const counts: Record<LogLevel, number> = { ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0, TRACE: 0, UNKNOWN: 0 }
    for (const log of logs) counts[log.level]++
    return counts
  }, [logs])

  // Exception count (from all logs)
  const exceptionCount = useMemo(() => {
    let count = 0
    for (const log of logs) {
      if (log.isExceptionStart) count++
    }
    return count
  }, [logs])

  // Error indices in displayed logs
  const errorIndices = useMemo(() => {
    const indices: number[] = []
    for (let i = 0; i < displayedLogs.length; i++) {
      if (displayedLogs[i].level === 'ERROR') indices.push(i)
    }
    return indices
  }, [displayedLogs])

  // Exception start indices in displayed logs
  const excIndices = useMemo(() => {
    const indices: number[] = []
    for (let i = 0; i < displayedLogs.length; i++) {
      if (displayedLogs[i].isExceptionStart) indices.push(i)
    }
    return indices
  }, [displayedLogs])

  // Row props for virtualized list
  const highlightedIndex = useMemo(() => {
    if (currentExcIdx >= 0 && currentExcIdx < excIndices.length) return excIndices[currentExcIdx]
    if (currentErrorIdx >= 0 && currentErrorIdx < errorIndices.length) return errorIndices[currentErrorIdx]
    return -1
  }, [currentErrorIdx, errorIndices, currentExcIdx, excIndices])

  const rowProps = useMemo<RowCustomProps>(() => ({
    logs: displayedLogs,
    search: deferredSearch,
    highlightedIndex,
    theme: lt,
  }), [displayedLogs, deferredSearch, highlightedIndex, lt])

  // Auto-scroll effect
  useEffect(() => {
    if (!autoScroll || displayedLogs.length === 0) return
    if (!wordWrap && listRef.current) {
      listRef.current.scrollToRow({ index: displayedLogs.length - 1, align: 'end' })
    }
    if (wordWrap && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [displayedLogs.length, autoScroll, wordWrap, listRef])

  // Scroll detection for virtualized list
  // Uses requestAnimationFrame retry because listRef.current.element may not
  // be available on the first render when the List component mounts.
  useEffect(() => {
    if (wordWrap || displayedLogs.length === 0) return

    let handler: (() => void) | null = null
    let attachedEl: HTMLElement | null = null
    let rafId: number | null = null

    function tryAttach() {
      const el = listRef.current?.element
      if (!el) {
        rafId = requestAnimationFrame(tryAttach)
        return
      }
      attachedEl = el
      handler = () => {
        const totalHeight = displayedCountRef.current * ROW_HEIGHT
        const isAtBottom = totalHeight - el.scrollTop - el.clientHeight < 50
        setAutoScroll(isAtBottom)
      }
      el.addEventListener('scroll', handler, { passive: true })
    }

    tryAttach()

    return () => {
      if (rafId !== null) cancelAnimationFrame(rafId)
      if (attachedEl && handler) attachedEl.removeEventListener('scroll', handler)
    }
  }, [wordWrap, listRef, displayedLogs.length])

  const handleWrapScroll = useCallback(() => {
    if (!scrollRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
    setAutoScroll(isAtBottom)
  }, [])

  const handleSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchTerm(e.target.value)
    setCurrentErrorIdx(-1)
    setCurrentExcIdx(-1)
  }, [])

  const toggleLevel = useCallback((level: LogLevel) => {
    setVisibleLevels(prev => {
      const next = new Set(prev)
      if (next.has(level)) next.delete(level)
      else next.add(level)
      return next
    })
    setCurrentErrorIdx(-1)
    setCurrentExcIdx(-1)
  }, [])

  const toggleExceptions = useCallback(() => {
    setShowExceptions(prev => !prev)
    setCurrentErrorIdx(-1)
    setCurrentExcIdx(-1)
  }, [])

  const scrollToIndex = useCallback((index: number) => {
    setAutoScroll(false)
    if (!wordWrap && listRef.current) {
      listRef.current.scrollToRow({ index, align: 'center' })
    } else if (wordWrap) {
      const el = scrollRef.current?.children[index] as HTMLElement | undefined
      el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
  }, [wordWrap, listRef])

  const jumpToNextError = useCallback(() => {
    if (errorIndices.length === 0) return
    const next = currentErrorIdx < errorIndices.length - 1 ? currentErrorIdx + 1 : 0
    setCurrentErrorIdx(next)
    setCurrentExcIdx(-1)
    scrollToIndex(errorIndices[next])
  }, [errorIndices, currentErrorIdx, scrollToIndex])

  const jumpToPrevError = useCallback(() => {
    if (errorIndices.length === 0) return
    const prev = currentErrorIdx > 0 ? currentErrorIdx - 1 : errorIndices.length - 1
    setCurrentErrorIdx(prev)
    setCurrentExcIdx(-1)
    scrollToIndex(errorIndices[prev])
  }, [errorIndices, currentErrorIdx, scrollToIndex])

  const jumpToNextExc = useCallback(() => {
    if (excIndices.length === 0) return
    const next = currentExcIdx < excIndices.length - 1 ? currentExcIdx + 1 : 0
    setCurrentExcIdx(next)
    setCurrentErrorIdx(-1)
    scrollToIndex(excIndices[next])
  }, [excIndices, currentExcIdx, scrollToIndex])

  const jumpToPrevExc = useCallback(() => {
    if (excIndices.length === 0) return
    const prev = currentExcIdx > 0 ? currentExcIdx - 1 : excIndices.length - 1
    setCurrentExcIdx(prev)
    setCurrentErrorIdx(-1)
    scrollToIndex(excIndices[prev])
  }, [excIndices, currentExcIdx, scrollToIndex])

  const scrollToBottom = useCallback(() => {
    if (!wordWrap && listRef.current && displayedLogs.length > 0) {
      listRef.current.scrollToRow({ index: displayedLogs.length - 1, align: 'end' })
    } else if (wordWrap && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
    setAutoScroll(true)
  }, [wordWrap, displayedLogs.length, listRef])

  const handleCopy = useCallback(async () => {
    const text = displayedLogs.map(l => l.message).join('\n')
    await copyToClipboard(text)
    setCopySnackbar(true)
  }, [displayedLogs])

  const togglePaused = useCallback(() => {
    const next = !pausedRef.current
    pausedRef.current = next
    setPaused(next)
    if (!next) {
      // Resume: flush accumulated logs immediately
      flushLogs()
      setPausedBufferCount(0)
    }
  }, [flushLogs])

  const clearLogs = useCallback(() => {
    setLogs([])
    pendingLogsRef.current = []
    setCurrentErrorIdx(-1)
    setCurrentExcIdx(-1)
  }, [])

  // Periodically update the buffered count while paused
  useEffect(() => {
    if (!paused) return
    const id = setInterval(() => {
      setPausedBufferCount(pendingLogsRef.current.length)
    }, 500)
    return () => clearInterval(id)
  }, [paused])

  function handleClose() {
    cleanupRef.current?.()
    cleanupRef.current = null
    if (flushTimerRef.current !== null) {
      clearTimeout(flushTimerRef.current)
      flushTimerRef.current = null
    }
    pendingLogsRef.current = []
    pausedRef.current = false
    setPaused(false)
    setPausedBufferCount(0)
    setConnected(false)
    setLogs([])
    resetFullScreen()
    onClose()
  }

  const hasActiveFilters = deferredSearch !== '' || visibleLevels.size < 6 || !showExceptions
  const wrapToggleActive = isDark ? '#4d96ff' : '#1565c0'

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="lg" fullWidth {...dialogProps}>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Terminal />
        <Typography variant="h6" component="span" sx={{ flex: 1 }}>
          {t('containers.logs.title', { name: containerName })}
        </Typography>
        <Chip
          label={paused
            ? t('containers.logs.paused', { count: pausedBufferCount })
            : connected ? t('containers.logs.connected') : t('containers.logs.disconnected')}
          color={paused ? 'warning' : connected ? 'success' : 'default'}
          size="small"
          variant="outlined"
        />
      </DialogTitle>

      <DialogContent dividers sx={{ p: 0, position: 'relative', ...contentSx }}>
        {/* Toolbar */}
        <Box
          sx={{
            bgcolor: lt.toolbarBg,
            px: 1.5,
            py: 0.75,
            display: 'flex',
            flexWrap: 'wrap',
            gap: 0.75,
            alignItems: 'center',
            borderBottom: `1px solid ${lt.toolbarBorder}`,
            flexShrink: 0,
          }}
        >
          {/* Search */}
          <TextField
            size="small"
            placeholder={t('containers.logs.searchPlaceholder')}
            value={searchTerm}
            onChange={handleSearchChange}
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

          {/* Level toggles */}
          <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
            {TOGGLE_LEVELS.map(level => {
              const color = lt.levelColors[level]
              const active = visibleLevels.has(level)
              return (
                <Chip
                  key={level}
                  label={`${level} ${levelCounts[level]}`}
                  size="small"
                  onClick={() => toggleLevel(level)}
                  sx={{
                    bgcolor: active ? color + '22' : 'transparent',
                    color: active ? color : lt.chipInactive,
                    borderColor: active ? color + '88' : lt.chipBorderInactive,
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
            {/* Exception toggle */}
            <Chip
              icon={<BugReport sx={{ fontSize: 14, color: showExceptions ? lt.excColor : lt.chipInactive }} />}
              label={`EXC ${exceptionCount}`}
              size="small"
              onClick={toggleExceptions}
              sx={{
                bgcolor: showExceptions ? lt.excColor + '22' : 'transparent',
                color: showExceptions ? lt.excColor : lt.chipInactive,
                borderColor: showExceptions ? lt.excColor + '88' : lt.chipBorderInactive,
                fontSize: '0.7rem',
                fontFamily: 'monospace',
                height: 24,
                cursor: 'pointer',
                '&:hover': { bgcolor: lt.excColor + '18' },
                '& .MuiChip-icon': { ml: '4px', mr: '-2px' },
              }}
              variant="outlined"
            />
          </Box>

          {/* Error navigation */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
            <Tooltip title={t('containers.logs.prevError')}>
              <span>
                <IconButton
                  onClick={jumpToPrevError}
                  disabled={errorIndices.length === 0}
                  size="small"
                  sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
                >
                  <KeyboardArrowUp sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Typography
              variant="caption"
              sx={{
                color: errorIndices.length > 0 ? lt.levelColors.ERROR : lt.chipInactive,
                fontFamily: 'monospace',
                minWidth: 55,
                textAlign: 'center',
              }}
            >
              {errorIndices.length > 0
                ? t('containers.logs.errorPosition', { current: currentErrorIdx + 1, total: errorIndices.length })
                : t('containers.logs.noErrors')}
            </Typography>
            <Tooltip title={t('containers.logs.nextError')}>
              <span>
                <IconButton
                  onClick={jumpToNextError}
                  disabled={errorIndices.length === 0}
                  size="small"
                  sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
                >
                  <KeyboardArrowDown sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
          </Box>

          {/* Exception navigation */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
            <Tooltip title={t('containers.logs.prevException')}>
              <span>
                <IconButton
                  onClick={jumpToPrevExc}
                  disabled={excIndices.length === 0}
                  size="small"
                  sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
                >
                  <KeyboardArrowUp sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Typography
              variant="caption"
              sx={{
                color: excIndices.length > 0 ? lt.excColor : lt.chipInactive,
                fontFamily: 'monospace',
                minWidth: 55,
                textAlign: 'center',
              }}
            >
              {excIndices.length > 0
                ? t('containers.logs.excPosition', { current: currentExcIdx + 1, total: excIndices.length })
                : t('containers.logs.noExceptions')}
            </Typography>
            <Tooltip title={t('containers.logs.nextException')}>
              <span>
                <IconButton
                  onClick={jumpToNextExc}
                  disabled={excIndices.length === 0}
                  size="small"
                  sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
                >
                  <KeyboardArrowDown sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
          </Box>

          <Box sx={{ flex: 1 }} />

          {/* Clear */}
          <Tooltip title={t('containers.logs.clear')}>
            <span>
              <IconButton
                onClick={clearLogs}
                disabled={logs.length === 0}
                size="small"
                sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
              >
                <ClearAll sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>

          {/* Pause/Resume */}
          <Tooltip title={paused
            ? t('containers.logs.resume', { count: pausedBufferCount })
            : t('containers.logs.pause')}
          >
            <IconButton
              onClick={togglePaused}
              disabled={!connected}
              size="small"
              sx={{
                color: paused ? lt.levelColors.WARN : lt.iconColor,
                bgcolor: paused ? lt.levelColors.WARN + '22' : 'transparent',
                '&.Mui-disabled': { color: lt.iconDisabled },
              }}
            >
              {paused ? <PlayArrow sx={{ fontSize: 18 }} /> : <Pause sx={{ fontSize: 18 }} />}
            </IconButton>
          </Tooltip>

          {/* Wrap toggle */}
          <Tooltip title={wordWrap ? t('containers.logs.nowrapLines') : t('containers.logs.wrapLines')}>
            <IconButton
              onClick={() => setWordWrap(w => !w)}
              size="small"
              sx={{
                color: wordWrap ? wrapToggleActive : lt.iconColor,
                bgcolor: wordWrap ? wrapToggleActive + '22' : 'transparent',
              }}
            >
              <WrapText sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>

          {/* Fullscreen toggle */}
          <FullscreenToggleButton fullScreen={fullScreen} onToggle={toggleFullScreen} color={lt.iconColor} />

          {/* Copy */}
          <Tooltip title={hasActiveFilters
            ? t('containers.logs.copyFiltered', { count: displayedLogs.length })
            : t('containers.logs.copyAll')}
          >
            <span>
              <IconButton
                onClick={handleCopy}
                disabled={displayedLogs.length === 0}
                size="small"
                sx={{ color: lt.iconColor, '&.Mui-disabled': { color: lt.iconDisabled } }}
              >
                <ContentCopy sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
        </Box>

        {streamError && (
          <Alert severity="error" sx={{ borderRadius: 0 }} onClose={() => setStreamError('')}>
            {streamError}
          </Alert>
        )}

        {/* Log viewer */}
        <Box sx={{ position: 'relative', ...viewerSx(CONTAINER_HEIGHT) }}>
          {displayedLogs.length === 0 ? (
            <Box sx={{ bgcolor: lt.logViewerBg, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Typography sx={{ color: lt.emptyText, fontFamily: 'monospace', fontSize: '0.85rem' }}>
                {logs.length === 0 && connected
                  ? t('containers.logs.waiting')
                  : logs.length > 0
                    ? t('containers.logs.noResults')
                    : ''}
              </Typography>
            </Box>
          ) : wordWrap ? (
            <Box
              ref={scrollRef}
              onScroll={handleWrapScroll}
              sx={{
                bgcolor: lt.logViewerBg,
                height: '100%',
                overflow: 'auto',
                px: 2,
                py: 1,
              }}
            >
              {displayedLogs.map((log, i) => {
                const isHighlighted = i === highlightedIndex
                const isException = log.isStackTrace || log.isExceptionStart

                return (
                  <Box
                    key={i}
                    component="div"
                    sx={{
                      color: log.isStackTrace ? lt.stackTraceColor : log.isExceptionStart ? lt.excColor : lt.levelColors[log.level],
                      fontStyle: log.isStackTrace ? 'italic' : 'normal',
                      fontWeight: log.isExceptionStart ? 600 : 'normal',
                      bgcolor: isHighlighted ? lt.excHighlightBg : isException ? lt.excSubtleBg : 'transparent',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                      fontFamily: '"Cascadia Code", "Fira Code", "JetBrains Mono", monospace',
                      fontSize: '0.8rem',
                      lineHeight: '20px',
                    }}
                  >
                    <HighlightedText text={log.message} search={deferredSearch} markColor={lt.highlightMark} />
                  </Box>
                )
              })}
            </Box>
          ) : (
            <List
              listRef={listRef}
              rowComponent={VirtualRow}
              rowCount={displayedLogs.length}
              rowHeight={ROW_HEIGHT}
              rowProps={rowProps}
              overscanCount={20}
              style={{ backgroundColor: lt.logViewerBg, height: '100%' }}
            />
          )}

          {/* Scroll to bottom button */}
          {!autoScroll && displayedLogs.length > 0 && (
            <Tooltip title={t('containers.logs.scrollToBottom')}>
              <IconButton
                onClick={scrollToBottom}
                sx={{
                  position: 'absolute',
                  bottom: 16,
                  right: 24,
                  bgcolor: 'primary.main',
                  color: 'white',
                  '&:hover': { bgcolor: 'primary.dark' },
                  zIndex: 1,
                }}
                size="small"
              >
                <KeyboardArrowDown />
              </IconButton>
            </Tooltip>
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ px: 3, py: 2 }}>
        {analyzerEnabled && canAnalyze && (
          <Tooltip title={t('containers.logs.deepAnalysisTooltip')} arrow>
            <span>
              <Button
                size="small"
                startIcon={<Analytics />}
                onClick={() => {
                  onClose()
                  navigate('/logs', { state: { openUpload: true, containerId, containerName } })
                }}
              >
                {t('containers.logs.deepAnalysis')}
              </Button>
            </span>
          </Tooltip>
        )}
        <Typography variant="caption" color="text.secondary" sx={{ flex: 1 }}>
          {displayedLogs.length === logs.length
            ? t('containers.logs.lineCount', { count: logs.length })
            : t('containers.logs.filteredLineCount', { filtered: displayedLogs.length, total: logs.length })}
        </Typography>
        <Button onClick={handleClose} color="inherit">{t('common.close')}</Button>
      </DialogActions>

      <Snackbar
        open={copySnackbar}
        autoHideDuration={2000}
        onClose={() => setCopySnackbar(false)}
        message={t('containers.logs.copied')}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Dialog>
  )
}
