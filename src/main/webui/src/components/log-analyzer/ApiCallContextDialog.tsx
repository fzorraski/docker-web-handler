import { useState, useEffect, useMemo, useCallback } from 'react'
import { copyToClipboard } from '../../utils/clipboard'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, IconButton,
  Box, Typography, Chip, CircularProgress, Stack, Tooltip, TablePagination,
  useTheme,
} from '@mui/material'
import { ContentCopy } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { LogLine } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

function levelColor(level: string | null): string | undefined {
  switch (level) {
    case 'ERROR': case 'FATAL': case 'SEVERE': return '#e53935'
    case 'WARN': case 'WARNING': return '#ef6c00'
    default: return undefined
  }
}

const ERROR_LEVELS = new Set(['ERROR', 'FATAL', 'SEVERE'])
const WARN_LEVELS = new Set(['WARN', 'WARNING'])

type LevelFilter = '' | 'ERROR' | 'WARN'

export function ApiCallContextDialog({ open, onClose, analysisId, from, to, endpoint }: {
  open: boolean; onClose: () => void; analysisId: string; from: number; to: number; endpoint: string
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const [lines, setLines] = useState<LogLine[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(false)
  const [levelFilter, setLevelFilter] = useState<LevelFilter>('')
  const [copied, setCopied] = useState(false)
  const pageSize = 500

  const fetchPage = useCallback((pg: number, level: LevelFilter) => {
    setLoading(true)
    logService.getLineRange(analysisId, from, to, { level: level || undefined, page: pg, size: pageSize })
      .then((res) => {
        setLines(res.data)
        setTotal(res.total)
        setPage(res.page)
      })
      .catch(() => { setLines([]); setTotal(0) })
      .finally(() => setLoading(false))
  }, [analysisId, from, to])

  useEffect(() => {
    if (!open || !analysisId || from === 0 || to === 0) return
    setLevelFilter('')
    setCopied(false)
    fetchPage(0, '')
  }, [open, analysisId, from, to, fetchPage])

  useEffect(() => {
    if (!open) { setLines([]); setTotal(0); setPage(0) }
  }, [open])

  // Count errors/warns from current page for chip display
  const errorCount = useMemo(() => lines.filter(l => l.level && ERROR_LEVELS.has(l.level)).length, [lines])
  const warnCount = useMemo(() => lines.filter(l => l.level && WARN_LEVELS.has(l.level)).length, [lines])

  const handleLevelChange = (level: LevelFilter) => {
    const newLevel = levelFilter === level ? '' : level
    setLevelFilter(newLevel)
    setPage(0)
    fetchPage(0, newLevel)
  }

  const handleCopy = () => {
    const text = lines.map(l => `${l.lineNumber}\t${l.message ?? ''}`).join('\n')
    copyToClipboard(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  const lineNumWidth = to > 0 ? String(to).length : 4

  return (
    <Dialog open={open} onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.9rem', display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box sx={{ flex: 1 }}>
          {t('logAnalyzer.apiCalls.contextTitle', { endpoint, from, to })}
          {total > 0 && <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>({total.toLocaleString()} lines)</Typography>}
        </Box>
        <Tooltip title={copied ? t('logAnalyzer.apiCalls.copied') : t('logAnalyzer.apiCalls.copyAll')}>
          <IconButton size="small" onClick={handleCopy} disabled={lines.length === 0}>
            <ContentCopy fontSize="small" />
          </IconButton>
        </Tooltip>
      </DialogTitle>
      <DialogContent dividers>
        {loading && lines.length === 0 ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress />
          </Box>
        ) : lines.length === 0 && !loading ? (
          <Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>
            {t('logAnalyzer.apiCalls.noLinesFound')}
          </Typography>
        ) : (
          <>
            <Stack direction="row" spacing={1} mb={1.5} alignItems="center">
              <Chip size="small" label={`${t('logAnalyzer.apiCalls.allLines')} (${total.toLocaleString()})`}
                color={levelFilter === '' ? 'primary' : 'default'}
                variant={levelFilter === '' ? 'filled' : 'outlined'}
                onClick={() => handleLevelChange('')} />
              {errorCount > 0 && (
                <Chip size="small" label={`Error (${errorCount})`}
                  color={levelFilter === 'ERROR' ? 'error' : 'default'}
                  variant={levelFilter === 'ERROR' ? 'filled' : 'outlined'}
                  onClick={() => handleLevelChange('ERROR')} />
              )}
              {warnCount > 0 && (
                <Chip size="small" label={`Warn (${warnCount})`}
                  color={levelFilter === 'WARN' ? 'warning' : 'default'}
                  variant={levelFilter === 'WARN' ? 'filled' : 'outlined'}
                  onClick={() => handleLevelChange('WARN')} />
              )}
              {loading && <CircularProgress size={16} />}
            </Stack>
            <Box sx={{
              maxHeight: 500,
              overflow: 'auto',
              bgcolor: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.02)',
              borderRadius: 1,
              fontFamily: "'JetBrains Mono', monospace",
              fontSize: '0.8rem',
            }}>
              {lines.map((line) => (
                <Box key={`${line.sourceFile}-${line.lineNumber}`} sx={{
                  display: 'flex', px: 1.5, py: '1px',
                  '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' },
                }}>
                  <Box sx={{
                    minWidth: lineNumWidth * 9 + 12,
                    textAlign: 'right',
                    pr: 1.5,
                    color: isDark ? 'rgba(255,255,255,0.35)' : 'rgba(0,0,0,0.35)',
                    userSelect: 'none',
                    flexShrink: 0,
                    lineHeight: '20px',
                  }}>
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
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ justifyContent: 'space-between' }}>
        {total > pageSize ? (
          <TablePagination
            component="div"
            count={total}
            page={page}
            onPageChange={(_, p) => fetchPage(p, levelFilter)}
            rowsPerPage={pageSize}
            rowsPerPageOptions={[pageSize]}
            showFirstButton showLastButton
          />
        ) : <Box />}
        <Button onClick={onClose}>{t('common.close')}</Button>
      </DialogActions>
    </Dialog>
  )
}
