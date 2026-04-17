import { useState, useEffect, useMemo, useRef, Fragment } from 'react'
import { copyToClipboard } from '../../utils/clipboard'
import {
  Autocomplete, Box, Button, CircularProgress, Collapse, IconButton, Typography, Chip, Paper, LinearProgress, Stack,
  Table, TableHead, TableRow, TableCell, TableBody, TableContainer,
  TablePagination, TextField, InputAdornment,
  useTheme,
} from '@mui/material'
import { BugReport, ErrorOutline, ExpandMore, ExpandLess, HelpOutline, TuneRounded, ContentCopy, Search } from '@mui/icons-material'
import Tooltip from '@mui/material/Tooltip'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { LineLink } from './LineLink'
import { truncatedTooltipProps } from './tooltipStyles'
import type { CriticalIssueSummary, BurstCategorySummary, BurstMeta, CriticalIssue } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function CriticalIssuesTab({ analysisId, onJumpToLine }: { analysisId: string; onJumpToLine?: (line: number) => void }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const headerTheme = useTableHeaderTheme()

  const [allSummaries, setAllSummaries] = useState<CriticalIssueSummary[]>([])
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const [filterCategory, setFilterCategory] = useState('')
  const [filterPattern, setFilterPattern] = useState('')
  const [searchText, setSearchText] = useState('')
  const [loading, setLoading] = useState(false)
  const [burstData, setBurstData] = useState<BurstCategorySummary[] | null>(null)
  const [burstLoading, setBurstLoading] = useState(false)
  // Paginated bursts list for expanded category
  const [expandedBurstCategory, setExpandedBurstCategory] = useState<string | null>(null)
  const [categoryBursts, setCategoryBursts] = useState<BurstMeta[]>([])
  const [categoryBurstsTotal, setCategoryBurstsTotal] = useState(0)
  const [categoryBurstsPage, setCategoryBurstsPage] = useState(0)
  const [categoryBurstsLoading, setCategoryBurstsLoading] = useState(false)
  // Paginated issues for expanded burst
  const [expandedBurstIdx, setExpandedBurstIdx] = useState<number | null>(null)
  const [burstIssues, setBurstIssues] = useState<CriticalIssue[]>([])
  const [burstIssuesTotal, setBurstIssuesTotal] = useState(0)
  const [burstIssuesPage, setBurstIssuesPage] = useState(0)
  const [burstIssuesLoading, setBurstIssuesLoading] = useState(false)
  const fetchGenRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    const gen = ++fetchGenRef.current
    logService.getCriticalIssues(analysisId).then((summaries) => {
      if (gen !== fetchGenRef.current) return
      setAllSummaries(summaries)
    }).catch(() => {}).finally(() => {
      if (gen === fetchGenRef.current) setLoading(false)
    })
  }, [analysisId])

  const categories = useMemo(() => {
    const set = new Set(allSummaries.map(s => s.category))
    return Array.from(set).sort()
  }, [allSummaries])

  const categorySummary = useMemo(() => {
    const map = new Map<string, { severity: string; count: number; totalIssues: number }>()
    for (const s of allSummaries) {
      const entry = map.get(s.category) ?? { severity: s.severity, count: 0, totalIssues: 0 }
      entry.count++
      entry.totalIssues += s.count
      // Keep highest severity
      if (s.severity === 'CRITICAL' || (s.severity === 'HIGH' && entry.severity !== 'CRITICAL')) {
        entry.severity = s.severity
      }
      map.set(s.category, entry)
    }
    return map
  }, [allSummaries])

  const severityChipColor = (severity: string): 'error' | 'warning' | 'default' => {
    if (severity === 'CRITICAL') return 'error'
    if (severity === 'HIGH') return 'warning'
    return 'default'
  }

  const severityLabel = (severity: string): string => {
    if (severity === 'CRITICAL') return t('logAnalyzer.criticalIssues.severityCritical')
    if (severity === 'HIGH') return t('logAnalyzer.criticalIssues.severityHigh')
    return t('logAnalyzer.criticalIssues.severityMedium')
  }

  const filtered = useMemo(() => {
    if (!filterCategory) return allSummaries
    return allSummaries.filter(s => s.category === filterCategory)
  }, [allSummaries, filterCategory])

  const patterns = useMemo(() => {
    const set = new Set(filtered.flatMap(s => s.issues.map(i => i.pattern)))
    return Array.from(set).sort()
  }, [filtered])

  // Flatten summaries into individual issues for the table
  const flatIssues = useMemo(() => {
    const term = searchText.trim().toLowerCase()
    return filtered.flatMap(s =>
      s.issues
        .filter(issue => !filterPattern || issue.pattern === filterPattern)
        .filter(issue => !term
          || issue.message.toLowerCase().includes(term)
          || issue.pattern.toLowerCase().includes(term)
          || issue.category.toLowerCase().includes(term)
          || issue.sourceFile.toLowerCase().includes(term))
        .map(issue => ({
          ...issue,
          categorySeverity: s.severity,
        }))
    )
  }, [filtered, filterPattern, searchText])

  const totalFlat = flatIssues.length
  const paged = flatIssues.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)

  const totalIssueCount = useMemo(() => allSummaries.reduce((sum, s) => sum + s.count, 0), [allSummaries])

  const [burstThreshold, setBurstThreshold] = useState(10)
  const [burstWindow, setBurstWindow] = useState(5)
  const [showBurstParams, setShowBurstParams] = useState(false)

  const handleAnalyzeBursts = () => {
    setBurstLoading(true)
    logService.getCriticalBursts(analysisId, burstThreshold, burstWindow)
      .then(setBurstData)
      .catch(() => setBurstData([]))
      .finally(() => setBurstLoading(false))
  }

  const fetchCategoryBursts = (category: string, pg: number) => {
    setCategoryBurstsLoading(true)
    logService.getCriticalBurstsByCategory(analysisId, category, { page: pg, size: 10 })
      .then((res) => {
        setCategoryBursts(res.data)
        setCategoryBurstsTotal(res.total)
        setCategoryBurstsPage(res.page)
      })
      .catch(() => { setCategoryBursts([]); setCategoryBurstsTotal(0) })
      .finally(() => setCategoryBurstsLoading(false))
  }

  const handleToggleBurstCategory = (category: string) => {
    if (expandedBurstCategory === category) {
      setExpandedBurstCategory(null)
      setCategoryBursts([])
      setExpandedBurstIdx(null)
      setBurstIssues([])
    } else {
      setExpandedBurstCategory(category)
      setExpandedBurstIdx(null)
      setBurstIssues([])
      setCategoryBurstsPage(0)
      fetchCategoryBursts(category, 0)
    }
  }

  const fetchBurstIssues = (category: string, burstIndex: number, pg: number) => {
    setBurstIssuesLoading(true)
    logService.getCriticalBurstIssues(analysisId, category, burstIndex, { page: pg, size: 25 })
      .then((res) => {
        setBurstIssues(res.data)
        setBurstIssuesTotal(res.total)
        setBurstIssuesPage(res.page)
      })
      .catch(() => { setBurstIssues([]); setBurstIssuesTotal(0) })
      .finally(() => setBurstIssuesLoading(false))
  }

  const handleToggleBurst = (category: string, globalBurstIndex: number) => {
    if (expandedBurstIdx === globalBurstIndex) {
      setExpandedBurstIdx(null)
      setBurstIssues([])
      setBurstIssuesTotal(0)
      setBurstIssuesPage(0)
    } else {
      setExpandedBurstIdx(globalBurstIndex)
      setBurstIssuesPage(0)
      fetchBurstIssues(category, globalBurstIndex, 0)
    }
  }

  return (
    <Box>
      {loading && <LinearProgress sx={{ mb: 1 }} />}

      {/* Burst analysis — on demand */}
      <Paper sx={{ p: 2, mb: 3, bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
        {burstData == null ? (
          <>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <ErrorOutline color="action" />
            <Box sx={{ flex: 1, minWidth: 200 }}>
              <Stack direction="row" spacing={0.5} alignItems="center">
                <Typography variant="subtitle2">{t('logAnalyzer.criticalIssues.burstAnalysis')}</Typography>
                <Tooltip title={t('logAnalyzer.criticalIssues.burstTooltip')} arrow placement="right">
                  <HelpOutline sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
                </Tooltip>
              </Stack>
              <Typography variant="caption" color="text.secondary">{t('logAnalyzer.criticalIssues.burstAnalysisDescription')}</Typography>
            </Box>
            <Tooltip title={t('logAnalyzer.criticalIssues.configureParams')} arrow>
              <IconButton size="small" onClick={() => setShowBurstParams(!showBurstParams)}
                color={showBurstParams ? 'primary' : 'default'}>
                <TuneRounded fontSize="small" />
              </IconButton>
            </Tooltip>
            <Button variant="outlined" size="small" onClick={handleAnalyzeBursts} disabled={burstLoading}>
              {burstLoading ? t('logAnalyzer.criticalIssues.analyzing') : t('logAnalyzer.criticalIssues.analyzeBursts')}
            </Button>
          </Stack>
          <Collapse in={showBurstParams}>
            <Stack direction="row" spacing={1.5} alignItems="center" mt={1.5} pl={5}>
              <TextField size="small" type="number" label={t('logAnalyzer.criticalIssues.threshold')}
                value={burstThreshold} onChange={(e) => setBurstThreshold(Math.max(2, Number(e.target.value)))}
                sx={{ width: 100 }} inputProps={{ min: 2, max: 1000 }} />
              <TextField size="small" type="number" label={t('logAnalyzer.criticalIssues.windowMinutes')}
                value={burstWindow} onChange={(e) => setBurstWindow(Math.max(1, Number(e.target.value)))}
                sx={{ width: 120 }} inputProps={{ min: 1, max: 60 }} />
            </Stack>
          </Collapse>
          {burstLoading && <LinearProgress sx={{ mt: 1 }} />}
          </>
        ) : burstData.length === 0 ? (
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" color="success.main" fontWeight={600}>{t('logAnalyzer.criticalIssues.noBurstsDetected')}</Typography>
            <Typography variant="caption" color="text.secondary">
              ({t('logAnalyzer.criticalIssues.thresholdLabel', { threshold: burstThreshold, window: burstWindow })})
            </Typography>
            <Tooltip title={t('logAnalyzer.criticalIssues.burstTooltip')} arrow>
              <HelpOutline sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
            </Tooltip>
            <Button size="small" onClick={() => setBurstData(null)}>{t('logAnalyzer.criticalIssues.changeParams')}</Button>
          </Stack>
        ) : (
          <Box>
            <Stack direction="row" spacing={1} alignItems="center" mb={2} flexWrap="wrap" useFlexGap>
              <ErrorOutline color="error" />
              <Typography variant="h6" fontWeight={700} color="error.main">
                {t('logAnalyzer.criticalIssues.detectedBursts')}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                ({t('logAnalyzer.criticalIssues.thresholdLabel', { threshold: burstThreshold, window: burstWindow })})
              </Typography>
              <Tooltip title={t('logAnalyzer.criticalIssues.burstTooltip')} arrow>
                <HelpOutline sx={{ fontSize: 16, color: 'text.secondary', cursor: 'help' }} />
              </Tooltip>
              <Box sx={{ flex: 1 }} />
              <Button size="small" onClick={() => setBurstData(null)}>{t('logAnalyzer.criticalIssues.changeParams')}</Button>
            </Stack>
            {burstData.map((data) => {
              const isCatExpanded = expandedBurstCategory === data.category
              return (
                <Paper
                  key={data.category}
                  variant="outlined"
                  sx={{ mb: 1, borderLeft: '3px solid', borderColor: 'error.main' }}
                >
                  <Stack
                    direction="row"
                    spacing={1.5}
                    alignItems="center"
                    flexWrap="wrap"
                    useFlexGap
                    sx={{ p: 1.5, cursor: 'pointer' }}
                    onClick={() => handleToggleBurstCategory(data.category)}
                  >
                    <Chip size="small" label={data.category} variant="outlined" />
                    <Chip size="small" label={severityLabel(data.severity)} color={severityChipColor(data.severity)} />
                    <Typography variant="body2" fontSize="0.85rem" fontWeight={600}>
                      {t('logAnalyzer.criticalIssues.burstSummary', { burstCount: data.burstCount, issueCount: data.totalBurstIssues.toLocaleString() })}
                    </Typography>
                    <Typography variant="body2" fontSize="0.85rem" color="text.secondary">
                      {data.firstStart.replace('T', ' ')} — {data.lastEnd.replace('T', ' ')}
                    </Typography>
                    <Box sx={{ ml: 'auto' }}>
                      {isCatExpanded ? <ExpandLess fontSize="small" /> : <ExpandMore fontSize="small" />}
                    </Box>
                  </Stack>
                  <Collapse in={isCatExpanded}>
                    <Box sx={{ pl: 3, pr: 1.5, pb: 1.5 }}>
                      {categoryBurstsLoading && <LinearProgress sx={{ mb: 1 }} />}
                      {categoryBursts.map((burst, localIdx) => {
                        const globalIdx = categoryBurstsPage * 10 + localIdx
                        const isBurstExpanded = expandedBurstIdx === globalIdx
                        return (
                          <Box key={globalIdx}>
                            <Stack
                              direction="row"
                              spacing={1}
                              alignItems="center"
                              sx={{
                                py: 0.75,
                                px: 1,
                                cursor: 'pointer',
                                borderRadius: 1,
                                '&:hover': { bgcolor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)' },
                                borderLeft: '2px solid',
                                borderColor: isBurstExpanded ? 'error.main' : 'divider',
                                mb: 0.5,
                              }}
                              onClick={() => handleToggleBurst(data.category, globalIdx)}
                            >
                              <Typography variant="body2" fontSize="0.83rem" fontWeight={600}>
                                {t('logAnalyzer.criticalIssues.burstNumber', { n: globalIdx + 1 })}:
                              </Typography>
                              <Typography variant="body2" fontSize="0.83rem" color="text.secondary">
                                {burst.burstStart?.replace('T', ' ') ?? '?'} — {burst.burstEnd?.replace('T', ' ') ?? '?'}
                              </Typography>
                              <Typography variant="body2" fontSize="0.83rem" fontWeight={500}>
                                ({(() => {
                                  if (!burst.burstStart || !burst.burstEnd) return `${burst.issueCount} issues`
                                  const mins = Math.max(1, Math.round((new Date(burst.burstEnd).getTime() - new Date(burst.burstStart).getTime()) / 60000))
                                  return `${mins} min — ${burst.issueCount.toLocaleString()} issues`
                                })()})
                              </Typography>
                              <Box sx={{ ml: 'auto' }}>
                                {isBurstExpanded ? <ExpandLess fontSize="small" /> : <ExpandMore fontSize="small" />}
                              </Box>
                            </Stack>
                            {isBurstExpanded && burstIssuesLoading && (
                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 2, pl: 3 }}>
                                <CircularProgress size={20} color="warning" />
                                <Typography variant="body2" color="text.secondary">{t('logAnalyzer.criticalIssues.loadingIssues')}</Typography>
                              </Box>
                            )}
                            <Collapse in={isBurstExpanded && burstIssues.length > 0}>
                              <Box sx={{ pl: 2, pb: 1 }}>
                                {burstIssues.length > 0 && (
                                  <>
                                    <TableContainer>
                                      <Table size="small">
                                        <TableHead>
                                          <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
                                            <TableCell>{t('logAnalyzer.criticalIssues.pattern')}</TableCell>
                                            <TableCell>{t('logAnalyzer.criticalIssues.timestamp')}</TableCell>
                                            <TableCell>{t('logAnalyzer.criticalIssues.line')}</TableCell>
                                            <TableCell>{t('logAnalyzer.criticalIssues.message')}</TableCell>
                                          </TableRow>
                                        </TableHead>
                                        <TableBody>
                                          {burstIssues.map((issue, iIdx) => (
                                            <TableRow key={iIdx} hover>
                                              <TableCell>
                                                <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">
                                                  {issue.pattern}
                                                </Typography>
                                              </TableCell>
                                              <TableCell>
                                                <Typography variant="body2" fontSize="0.8rem">
                                                  {issue.timestamp?.replace('T', ' ') ?? '-'}
                                                </Typography>
                                              </TableCell>
                                              <TableCell>
                                                {onJumpToLine ? (
                                                  <LineLink line={issue.lineNumber} onClick={onJumpToLine} />
                                                ) : (
                                                  <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">
                                                    {issue.lineNumber}
                                                  </Typography>
                                                )}
                                              </TableCell>
                                              <TableCell>
                                                <Tooltip title={issue.message} arrow enterDelay={300}
                                                  slotProps={truncatedTooltipProps}>
                                                  <Typography variant="body2" fontSize="0.8rem" sx={{
                                                    maxWidth: 400,
                                                    overflow: 'hidden',
                                                    textOverflow: 'ellipsis',
                                                    whiteSpace: 'nowrap',
                                                  }}>
                                                    {issue.message}
                                                  </Typography>
                                                </Tooltip>
                                              </TableCell>
                                            </TableRow>
                                          ))}
                                        </TableBody>
                                      </Table>
                                    </TableContainer>
                                    <TablePagination
                                      component="div"
                                      count={burstIssuesTotal}
                                      page={burstIssuesPage}
                                      onPageChange={(_, p) => {
                                        setBurstIssuesPage(p)
                                        fetchBurstIssues(data.category, globalIdx, p)
                                      }}
                                      rowsPerPage={25}
                                      rowsPerPageOptions={[25]}
                                      showFirstButton
                                      showLastButton
                                    />
                                  </>
                                )}
                              </Box>
                            </Collapse>
                          </Box>
                        )
                      })}
                      {categoryBurstsTotal > 10 && (
                        <TablePagination
                          component="div"
                          count={categoryBurstsTotal}
                          page={categoryBurstsPage}
                          onPageChange={(_, p) => {
                            setCategoryBurstsPage(p)
                            setExpandedBurstIdx(null)
                            setBurstIssues([])
                            fetchCategoryBursts(data.category, p)
                          }}
                          rowsPerPage={10}
                          rowsPerPageOptions={[10]}
                          showFirstButton
                          showLastButton
                        />
                      )}
                    </Box>
                  </Collapse>
                </Paper>
              )
            })}
          </Box>
        )}
      </Paper>

      {/* Summary */}
      <Stack direction="row" spacing={2} mb={2} flexWrap="wrap" useFlexGap alignItems="center">
        <Paper sx={{ px: 2, py: 1 }}>
          <Typography variant="caption" color="text.secondary">{t('logAnalyzer.criticalIssues.totalIssues')}</Typography>
          <Typography variant="h6" fontWeight={700} color="error.main">{totalIssueCount.toLocaleString()}</Typography>
        </Paper>
        {Array.from(categorySummary.entries()).map(([category, data]) => (
          <Chip
            key={category}
            icon={<BugReport />}
            label={`${category}: ${data.totalIssues.toLocaleString()}`}
            size="small"
            color={filterCategory === category ? 'primary' : severityChipColor(data.severity)}
            variant={filterCategory === category ? 'filled' : 'outlined'}
            onClick={() => { setFilterCategory(filterCategory === category ? '' : category); setPage(0); setExpandedIdx(null) }}
            sx={{ cursor: 'pointer' }}
          />
        ))}
      </Stack>

      {/* Filter */}
      <Stack direction="row" spacing={2} mb={2} alignItems="center" flexWrap="wrap" useFlexGap>
        <Autocomplete
          size="small"
          sx={{ minWidth: 220 }}
          options={categories}
          value={filterCategory || null}
          onChange={(_, v) => { setFilterCategory(v ?? ''); setFilterPattern(''); setPage(0); setExpandedIdx(null) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.criticalIssues.filterByCategory')} />}
        />
        <Autocomplete
          size="small"
          sx={{ minWidth: 280 }}
          options={patterns}
          value={filterPattern || null}
          onChange={(_, v) => { setFilterPattern(v ?? ''); setPage(0); setExpandedIdx(null) }}
          renderInput={(params) => <TextField {...params} label={t('logAnalyzer.criticalIssues.filterByPattern')} />}
        />
        <TextField
          size="small"
          placeholder={t('logAnalyzer.criticalIssues.search')}
          value={searchText}
          onChange={(e) => { setSearchText(e.target.value); setPage(0); setExpandedIdx(null) }}
          sx={{ minWidth: 250 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search sx={{ fontSize: 18, color: 'text.disabled' }} />
                </InputAdornment>
              ),
            },
          }}
        />
      </Stack>

      {flatIssues.length === 0 && !loading && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
          {t('logAnalyzer.criticalIssues.noIssues')}
        </Typography>
      )}

      {flatIssues.length > 0 && (
        <>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ bgcolor: headerTheme.theadBg, '& th': { color: headerTheme.theadColor } }}>
                  <TableCell>{t('logAnalyzer.criticalIssues.category')}</TableCell>
                  <TableCell>{t('logAnalyzer.criticalIssues.pattern')}</TableCell>
                  <TableCell>{t('logAnalyzer.criticalIssues.severity')}</TableCell>
                  <TableCell>{t('logAnalyzer.criticalIssues.timestamp')}</TableCell>
                  <TableCell>{t('logAnalyzer.criticalIssues.line')}</TableCell>
                  <TableCell>{t('logAnalyzer.criticalIssues.message')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {paged.map((issue, i) => {
                  const globalIdx = page * rowsPerPage + i
                  const isExpanded = expandedIdx === globalIdx

                  return (
                    <Fragment key={globalIdx}>
                      <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => {
                        setExpandedIdx(isExpanded ? null : globalIdx)
                      }}>
                        <TableCell>
                          <Chip size="small" label={issue.category} variant="outlined" />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">
                            {issue.pattern}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Chip size="small" label={severityLabel(issue.categorySeverity)} color={severityChipColor(issue.categorySeverity)} />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontSize="0.8rem">
                            {issue.timestamp?.replace('T', ' ') ?? '-'}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          {onJumpToLine ? (
                            <LineLink line={issue.lineNumber} onClick={onJumpToLine} />
                          ) : (
                            <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.8rem">
                              {issue.lineNumber}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <Tooltip title={issue.message} arrow enterDelay={300}
                            slotProps={truncatedTooltipProps}>
                            <Typography variant="body2" fontSize="0.8rem" sx={{
                              maxWidth: 400,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}>
                              {issue.message}
                            </Typography>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                      {isExpanded && (
                        <TableRow>
                          <TableCell colSpan={6} sx={{ bgcolor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)' }}>
                            <Box sx={{ maxHeight: 300, overflowY: 'auto', p: 1, position: 'relative' }}>
                              <Tooltip title={t('logAnalyzer.criticalIssues.copyMessage')} arrow>
                                <IconButton size="small"
                                  sx={{ position: 'absolute', top: 4, right: 4, opacity: 0.6, '&:hover': { opacity: 1 } }}
                                  onClick={(e) => { e.stopPropagation(); copyToClipboard(`[${issue.category}] ${issue.pattern}\nTimestamp: ${issue.timestamp?.replace('T', ' ') ?? '-'}\nLine: ${issue.lineNumber}\nSource: ${issue.sourceFile}\n\n${issue.message}`).catch(() => {}) }}>
                                  <ContentCopy sx={{ fontSize: 14 }} />
                                </IconButton>
                              </Tooltip>
                              <Typography variant="caption" color="text.secondary" display="block" mb={0.5}>
                                {t('logAnalyzer.criticalIssues.sourceFile')}: {issue.sourceFile}
                              </Typography>
                              <Typography variant="body2" fontFamily="'JetBrains Mono', monospace" fontSize="0.75rem"
                                sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                                {issue.message}
                              </Typography>
                            </Box>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination component="div" count={totalFlat} page={page} onPageChange={(_, p) => setPage(p)}
            rowsPerPage={rowsPerPage} onRowsPerPageChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
            showFirstButton showLastButton />
        </>
      )}
    </Box>
  )
}
