import { useState, useMemo, useCallback, useRef, useEffect } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
  Box,
  Paper,
  Chip,
  CircularProgress,
  IconButton,
  Tooltip,
  TextField,
  InputAdornment,
  Snackbar,
  useTheme,
} from '@mui/material'
import {
  Close,
  CheckCircle,
  Cancel,
  SwapHoriz,
  ContentCopy,
  Search,
  KeyboardArrowUp,
  KeyboardArrowDown,
  WrapText,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { MigrationPreview } from '../services/containerService'

const SQL_TYPES = ['ALTER', 'CREATE', 'INSERT', 'UPDATE', 'DELETE', 'DROP'] as const
type SqlType = typeof SQL_TYPES[number]

const TYPE_COLORS: Record<SqlType, string> = {
  ALTER: '#42a5f5',
  CREATE: '#66bb6a',
  INSERT: '#ab47bc',
  UPDATE: '#ffa726',
  DELETE: '#ef5350',
  DROP: '#f44336',
}

interface ParsedLine {
  text: string
  type: SqlType | 'COMMENT' | 'OTHER'
}

function classifyLine(line: string): ParsedLine['type'] {
  const trimmed = line.trim().toUpperCase()
  if (trimmed.startsWith('--')) return 'COMMENT'
  for (const t of SQL_TYPES) {
    if (trimmed.startsWith(t)) return t
  }
  return 'OTHER'
}

interface Props {
  open: boolean
  preview: MigrationPreview | null
  loading: boolean
  error?: string
  onApprove: () => void
  onDecline: () => void
}

export default function MigrationPreviewModal({ open, preview, loading, error, onApprove, onDecline }: Props) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const [searchTerm, setSearchTerm] = useState('')
  const [visibleTypes, setVisibleTypes] = useState<Set<SqlType | 'COMMENT' | 'OTHER'>>(
    () => new Set([...SQL_TYPES, 'COMMENT', 'OTHER'])
  )
  const [wordWrap, setWordWrap] = useState(false)
  const [copySnackbar, setCopySnackbar] = useState(false)
  const [currentNavType, setCurrentNavType] = useState<SqlType | null>(null)
  const [currentNavIdx, setCurrentNavIdx] = useState(-1)
  const scrollRef = useRef<HTMLDivElement>(null)

  // Reset state when modal opens
  useEffect(() => {
    if (open) {
      setSearchTerm('')
      setVisibleTypes(new Set([...SQL_TYPES, 'COMMENT', 'OTHER']))
      setWordWrap(false)
      setCurrentNavType(null)
      setCurrentNavIdx(-1)
    }
  }, [open])

  // Parse lines
  const parsedLines = useMemo<ParsedLine[]>(() => {
    if (!preview?.sql) return []
    return preview.sql.split('\n').map(text => ({ text, type: classifyLine(text) }))
  }, [preview?.sql])

  // Type counts (from all lines)
  const typeCounts = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const t of SQL_TYPES) counts[t] = 0
    for (const line of parsedLines) {
      if (line.type !== 'COMMENT' && line.type !== 'OTHER') {
        counts[line.type]++
      }
    }
    return counts as Record<SqlType, number>
  }, [parsedLines])

  // Filtered lines
  const displayedLines = useMemo(() => {
    let lines = parsedLines.filter(l => visibleTypes.has(l.type))
    if (searchTerm) {
      const lower = searchTerm.toLowerCase()
      lines = lines.filter(l => l.text.toLowerCase().includes(lower))
    }
    return lines
  }, [parsedLines, visibleTypes, searchTerm])

  // Navigation indices for current type
  const navIndices = useMemo(() => {
    if (!currentNavType) return []
    const indices: number[] = []
    for (let i = 0; i < displayedLines.length; i++) {
      if (displayedLines[i].type === currentNavType) indices.push(i)
    }
    return indices
  }, [displayedLines, currentNavType])

  const toggleType = useCallback((type: SqlType) => {
    setVisibleTypes(prev => {
      const next = new Set(prev)
      if (next.has(type)) next.delete(type)
      else next.add(type)
      return next
    })
    setCurrentNavType(null)
    setCurrentNavIdx(-1)
  }, [])

  const scrollToLine = useCallback((index: number) => {
    if (!scrollRef.current) return
    const el = scrollRef.current.children[index] as HTMLElement | undefined
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [])

  const navigateType = useCallback((type: SqlType, direction: 'next' | 'prev') => {
    // Build indices if navigating a different type than current
    let indices: number[]
    if (type === currentNavType) {
      indices = navIndices
    } else {
      indices = []
      for (let i = 0; i < displayedLines.length; i++) {
        if (displayedLines[i].type === type) indices.push(i)
      }
    }
    if (indices.length === 0) return

    let nextIdx: number
    if (currentNavType !== type) {
      nextIdx = direction === 'next' ? 0 : indices.length - 1
    } else {
      if (direction === 'next') {
        nextIdx = currentNavIdx < indices.length - 1 ? currentNavIdx + 1 : 0
      } else {
        nextIdx = currentNavIdx > 0 ? currentNavIdx - 1 : indices.length - 1
      }
    }

    setCurrentNavType(type)
    setCurrentNavIdx(nextIdx)
    scrollToLine(indices[nextIdx])
  }, [displayedLines, navIndices, currentNavType, currentNavIdx, scrollToLine])

  const handleCopy = useCallback(async () => {
    const text = displayedLines.map(l => l.text).join('\n')
    try {
      await navigator.clipboard.writeText(text)
    } catch { /* clipboard API may not be available */ }
    setCopySnackbar(true)
  }, [displayedLines])

  const highlightedLineIndex = useMemo(() => {
    if (!currentNavType || currentNavIdx < 0 || currentNavIdx >= navIndices.length) return -1
    return navIndices[currentNavIdx] ?? -1
  }, [navIndices, currentNavType, currentNavIdx])

  const toolbarBg = isDark ? '#1a1a2e' : '#f5f5f5'
  const toolbarBorder = isDark ? '#333' : '#ddd'
  const chipInactive = isDark ? '#666' : '#aaa'
  const chipBorderInactive = isDark ? '#444' : '#ccc'
  const iconColor = isDark ? '#aaa' : '#666'
  const iconDisabled = isDark ? '#444' : '#ccc'
  const searchBg = isDark ? '#0d1117' : '#fff'
  const wrapActiveColor = isDark ? '#4d96ff' : '#1565c0'
  const logBg = isDark ? '#0d1117' : '#fafafa'

  function getLineColor(type: ParsedLine['type']): string {
    if (type === 'COMMENT') return isDark ? '#6a737d' : '#999'
    if (type === 'OTHER') return isDark ? '#c9d1d9' : '#333'
    return TYPE_COLORS[type]
  }

  return (
    <Dialog open={open} onClose={onDecline} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        <SwapHoriz sx={{ mr: 1 }} /> {t('migrationPreview.title')}
        <Button onClick={onDecline} sx={{ ml: 'auto', color: 'white', minWidth: 'auto' }}>
          <Close />
        </Button>
      </DialogTitle>
      <DialogContent dividers sx={{ p: 0 }}>
        {loading && (
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', py: 6 }}>
            <CircularProgress sx={{ mr: 2 }} />
            <Typography>{t('migrationPreview.loading')}</Typography>
          </Box>
        )}

        {error && (
          <Typography color="error" variant="body1" sx={{ py: 4, textAlign: 'center' }}>
            {error}
          </Typography>
        )}

        {!loading && !error && preview && (
          <>
            {/* Overview */}
            <Box sx={{ px: 2, pt: 2, pb: 1, display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              {preview.sourceVersion && preview.targetVersion && (
                <Chip
                  label={`${preview.sourceVersion} \u2192 ${preview.targetVersion}`}
                  color="primary"
                  variant="outlined"
                  size="small"
                />
              )}
              <Chip
                label={t('migrationPreview.statements', { count: preview.totalStatements ?? 0 })}
                color="info"
                variant="outlined"
                size="small"
              />
              <Chip
                label={t('migrationPreview.characters', { count: preview.sql.length })}
                variant="outlined"
                size="small"
              />
              {preview.versionsIncluded && preview.versionsIncluded.length > 0 && (
                <>
                  <Box sx={{ width: '100%' }} />
                  <Typography variant="caption" color="text.secondary" sx={{ mr: 0.5, alignSelf: 'center' }}>
                    {t('migrationPreview.versionsIncluded')}:
                  </Typography>
                  {preview.versionsIncluded.map((v) => (
                    <Chip key={v} label={v} size="small" variant="outlined" />
                  ))}
                </>
              )}
            </Box>

            {/* Toolbar */}
            <Box
              sx={{
                bgcolor: toolbarBg,
                px: 1.5,
                py: 0.75,
                display: 'flex',
                flexWrap: 'wrap',
                gap: 0.75,
                alignItems: 'center',
                borderTop: `1px solid ${toolbarBorder}`,
                borderBottom: `1px solid ${toolbarBorder}`,
              }}
            >
              {/* Search */}
              <TextField
                size="small"
                placeholder={t('migrationPreview.searchPlaceholder')}
                value={searchTerm}
                onChange={(e) => { setSearchTerm(e.target.value); setCurrentNavType(null); setCurrentNavIdx(-1) }}
                slotProps={{
                  input: {
                    startAdornment: (
                      <InputAdornment position="start">
                        <Search sx={{ color: iconColor, fontSize: 18 }} />
                      </InputAdornment>
                    ),
                    sx: {
                      bgcolor: searchBg,
                      fontSize: '0.8rem',
                      height: 32,
                      '& .MuiOutlinedInput-notchedOutline': { borderColor: toolbarBorder },
                    },
                  },
                }}
                sx={{ minWidth: 160, maxWidth: 220 }}
              />

              {/* SQL type chips */}
              <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
                {SQL_TYPES.map(type => {
                  const color = TYPE_COLORS[type]
                  const active = visibleTypes.has(type)
                  const count = typeCounts[type]
                  return (
                    <Chip
                      key={type}
                      label={`${type} ${count}`}
                      size="small"
                      onClick={() => toggleType(type)}
                      sx={{
                        bgcolor: active ? color + '22' : 'transparent',
                        color: active ? color : chipInactive,
                        borderColor: active ? color + '88' : chipBorderInactive,
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

              {/* Type navigation */}
              {currentNavType && (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
                  <Tooltip title={t('migrationPreview.prevType', { type: currentNavType })}>
                    <span>
                      <IconButton
                        onClick={() => navigateType(currentNavType, 'prev')}
                        disabled={navIndices.length === 0}
                        size="small"
                        sx={{ color: iconColor, '&.Mui-disabled': { color: iconDisabled } }}
                      >
                        <KeyboardArrowUp sx={{ fontSize: 18 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Typography
                    variant="caption"
                    sx={{
                      color: TYPE_COLORS[currentNavType],
                      fontFamily: 'monospace',
                      minWidth: 55,
                      textAlign: 'center',
                    }}
                  >
                    {t('migrationPreview.typePosition', {
                      current: currentNavIdx + 1,
                      total: navIndices.length,
                      type: currentNavType,
                    })}
                  </Typography>
                  <Tooltip title={t('migrationPreview.nextType', { type: currentNavType })}>
                    <span>
                      <IconButton
                        onClick={() => navigateType(currentNavType, 'next')}
                        disabled={navIndices.length === 0}
                        size="small"
                        sx={{ color: iconColor, '&.Mui-disabled': { color: iconDisabled } }}
                      >
                        <KeyboardArrowDown sx={{ fontSize: 18 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Box>
              )}

              <Box sx={{ flex: 1 }} />

              {/* Wrap toggle */}
              <Tooltip title={wordWrap ? t('migrationPreview.nowrapLines') : t('migrationPreview.wrapLines')}>
                <IconButton
                  onClick={() => setWordWrap(w => !w)}
                  size="small"
                  sx={{
                    color: wordWrap ? wrapActiveColor : iconColor,
                    bgcolor: wordWrap ? wrapActiveColor + '22' : 'transparent',
                  }}
                >
                  <WrapText sx={{ fontSize: 18 }} />
                </IconButton>
              </Tooltip>

              {/* Copy */}
              <Tooltip title={t('migrationPreview.copyScript')}>
                <span>
                  <IconButton
                    onClick={handleCopy}
                    disabled={displayedLines.length === 0}
                    size="small"
                    sx={{ color: iconColor, '&.Mui-disabled': { color: iconDisabled } }}
                  >
                    <ContentCopy sx={{ fontSize: 18 }} />
                  </IconButton>
                </span>
              </Tooltip>
            </Box>

            {/* SQL viewer */}
            <Box sx={{ position: 'relative', height: 400 }}>
              {displayedLines.length === 0 ? (
                <Box sx={{ bgcolor: logBg, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Typography sx={{ color: chipInactive, fontFamily: 'monospace', fontSize: '0.85rem' }}>
                    {t('migrationPreview.noResults')}
                  </Typography>
                </Box>
              ) : (
                <Box
                  ref={scrollRef}
                  sx={{
                    bgcolor: logBg,
                    height: '100%',
                    overflow: 'auto',
                    px: 2,
                    py: 1,
                  }}
                >
                  {displayedLines.map((line, i) => (
                    <Box
                      key={i}
                      component="div"
                      onClick={() => {
                        if (line.type !== 'COMMENT' && line.type !== 'OTHER') {
                          // Find position of this line within its type
                          let typeIdx = 0
                          for (let j = 0; j < i; j++) {
                            if (displayedLines[j].type === line.type) typeIdx++
                          }
                          setCurrentNavType(line.type)
                          setCurrentNavIdx(typeIdx)
                        }
                      }}
                      sx={{
                        color: getLineColor(line.type),
                        whiteSpace: wordWrap ? 'pre-wrap' : 'pre',
                        wordBreak: wordWrap ? 'break-all' : undefined,
                        overflow: wordWrap ? undefined : 'hidden',
                        textOverflow: wordWrap ? undefined : 'ellipsis',
                        fontFamily: '"Cascadia Code", "Fira Code", "JetBrains Mono", monospace',
                        fontSize: '0.8rem',
                        lineHeight: '20px',
                        bgcolor: i === highlightedLineIndex
                          ? (isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)')
                          : 'transparent',
                        cursor: line.type !== 'COMMENT' && line.type !== 'OTHER' ? 'pointer' : 'default',
                        '&:hover': line.type !== 'COMMENT' && line.type !== 'OTHER'
                          ? { bgcolor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)' }
                          : {},
                      }}
                    >
                      {searchTerm ? highlightSearch(line.text, searchTerm, isDark) : line.text}
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {!loading && !error && preview && (
          <Typography variant="caption" color="text.secondary" sx={{ flex: 1 }}>
            {displayedLines.length === parsedLines.length
              ? t('migrationPreview.lineCount', { count: parsedLines.length })
              : t('migrationPreview.filteredLineCount', { filtered: displayedLines.length, total: parsedLines.length })}
          </Typography>
        )}
        <Button
          onClick={onDecline}
          color="error"
          variant="outlined"
          startIcon={<Cancel />}
        >
          {t('migrationPreview.decline')}
        </Button>
        <Button
          onClick={onApprove}
          color="success"
          variant="contained"
          disabled={loading || !!error || !preview}
          startIcon={<CheckCircle />}
        >
          {t('migrationPreview.approve')}
        </Button>
      </DialogActions>

      <Snackbar
        open={copySnackbar}
        autoHideDuration={2000}
        onClose={() => setCopySnackbar(false)}
        message={t('migrationPreview.copied')}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Dialog>
  )
}

function highlightSearch(text: string, search: string, isDark: boolean): React.ReactNode {
  const markColor = isDark ? '#e2b714' : '#fff3b0'
  const lower = text.toLowerCase()
  const lowerSearch = search.toLowerCase()
  const parts: (string | React.ReactElement)[] = []
  let lastIndex = 0
  let idx = lower.indexOf(lowerSearch, lastIndex)
  let key = 0

  while (idx !== -1) {
    if (idx > lastIndex) parts.push(text.slice(lastIndex, idx))
    parts.push(
      <mark key={key++} style={{ backgroundColor: markColor, color: '#000', borderRadius: 2, padding: '0 1px' }}>
        {text.slice(idx, idx + search.length)}
      </mark>
    )
    lastIndex = idx + search.length
    idx = lower.indexOf(lowerSearch, lastIndex)
  }
  if (lastIndex < text.length) parts.push(text.slice(lastIndex))

  return <>{parts}</>
}
