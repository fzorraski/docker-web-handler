import { useState, useCallback, useRef, useEffect, memo } from 'react'
import type { ManagedDatabaseInfo, QueryResult } from '../types'
import { executeQuery, explainQuery } from '../services/managedDatabaseService'
import type { DatabaseTableStats } from '../types'
import { copyToClipboard } from '../utils/clipboard'
import { useNotification } from './NotificationProvider'
import { useAuth } from './AuthProvider'
import FullscreenToggleButton from './FullscreenToggleButton'
import { useTranslation } from 'react-i18next'
import {
  Box,
  Typography,
  TextField,
  Button,
  Dialog,
  DialogTitle,
  DialogContent,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  CircularProgress,
  Alert,
  Stack,
  Chip,
  InputAdornment,
  IconButton,
  Tooltip,
  TablePagination,
  Menu,
  MenuItem,
  Tabs,
  Tab,
  LinearProgress,
} from '@mui/material'
import {
  PlayArrow,
  ContentCopy,
  Download,
  Close,
  Code,
  History,
  Clear,
  Lock,
  Speed,
  Warning,
  ErrorOutline,
  AccountTree,
} from '@mui/icons-material'

interface Props {
  open: boolean
  database: ManagedDatabaseInfo | null
  repository: string
  writeEnabled: boolean
  onClose: () => void
}

const HISTORY_KEY = 'queryRunner_history_'
const MAX_HISTORY = 10

const ROW_NUM_HEADER_SX = { fontWeight: 700, fontSize: '0.75rem', py: 0.5, bgcolor: 'background.paper', minWidth: 40 } as const
const COL_HEADER_SX = { fontWeight: 700, fontSize: '0.75rem', py: 0.5, bgcolor: 'background.paper', whiteSpace: 'nowrap' } as const
const ROW_NUM_CELL_SX = { color: 'text.secondary', fontSize: '0.7rem', py: 0.5 } as const
const DATA_CELL_SX = {
  fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5,
  maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
} as const
const NULL_CELL_SX = { ...DATA_CELL_SX, fontStyle: 'italic', color: 'text.disabled' } as const

function getHistory(dbKey: string): string[] {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY + dbKey) || '[]')
  } catch { return [] }
}

function saveHistory(dbKey: string, sql: string) {
  const history = getHistory(dbKey).filter(h => h !== sql)
  history.unshift(sql)
  if (history.length > MAX_HISTORY) history.pop()
  localStorage.setItem(HISTORY_KEY + dbKey, JSON.stringify(history))
}

export default function QueryRunnerDialog({ open, database, repository, writeEnabled, onClose }: Props) {
  const { notify } = useNotification()
  const { rbacEnabled } = useAuth()
  const { t } = useTranslation()
  const [sql, setSql] = useState('')
  const sqlRef = useRef(sql)
  useEffect(() => { sqlRef.current = sql }, [sql])
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<QueryResult | null>(null)
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(100)
  const [fullScreen, setFullScreen] = useState(false)
  const [activeTab, setActiveTab] = useState(0) // 0=results, 1=plan
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [planData, setPlanData] = useState<any>(null)
  const [tableStats, setTableStats] = useState<DatabaseTableStats | null>(null)
  const [historyAnchor, setHistoryAnchor] = useState<HTMLElement | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const cachedTotalRef = useRef<number | undefined>(undefined)

  const dbName = database?.name ?? ''
  const dbKey = repository + '/' + dbName

  // Reset state when switching databases
  useEffect(() => {
    setSql('')
    setPassword('')
    setResult(null)
    setError('')
    setPage(0)
    setPlanData(null)
    setActiveTab(0)
  }, [dbName])

  const isWriteQuery = useCallback((s: string) => {
    const upper = s.trim().toUpperCase()
    return upper.startsWith('INSERT') || upper.startsWith('UPDATE') || upper.startsWith('DELETE')
  }, [])

  const needsPassword = isWriteQuery(sql) && writeEnabled && !rbacEnabled

  const handleExecute = useCallback(async (p?: number) => {
    const currentSql = sqlRef.current
    if (!currentSql.trim() || !database) return
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    const currentPage = p ?? 0
    setLoading(true)
    setError('')
    if (p !== undefined) setPage(p)
    else setPage(0)

    // Pass cached totalRows on page changes so the backend skips COUNT(*) OVER()
    const cachedTotal = p !== undefined ? cachedTotalRef.current : undefined
    if (p === undefined) cachedTotalRef.current = undefined

    const pw = (isWriteQuery(currentSql) && writeEnabled) ? password : undefined
    try {
      const res = await executeQuery(repository, dbName, currentSql, currentPage, pageSize, pw, controller.signal, cachedTotal)
      setLoading(false)
      if (res.success && res.result) {
        setResult(res.result)
        cachedTotalRef.current = res.result.totalRows
        saveHistory(dbKey, currentSql.trim())
      } else {
        setError(res.error || t('common.unexpectedError'))
        setResult(null)
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setLoading(false)
      setError(e instanceof Error ? e.message : t('common.unexpectedError'))
    }
  }, [database, repository, dbName, pageSize, password, writeEnabled, isWriteQuery, dbKey, t])

  const handleExplain = useCallback(async (analyze: boolean) => {
    const currentSql = sqlRef.current
    if (!currentSql.trim() || !database) return
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setLoading(true)
    setError('')
    setPlanData(null)

    try {
      const res = await explainQuery(repository, dbName, currentSql.trim(), analyze, controller.signal)
      setLoading(false)
      setTableStats(res.tableStats ?? null)
      if (res.success && res.plan) {
        try {
          const parsed = JSON.parse(res.plan)
          setPlanData(parsed)
          setActiveTab(1)
          saveHistory(dbKey, currentSql.trim())
        } catch {
          setError('Failed to parse explain output.')
        }
      } else {
        setError(res.error || t('common.unexpectedError'))
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setLoading(false)
      setError(e instanceof Error ? e.message : t('common.unexpectedError'))
    }
  }, [database, repository, dbName, dbKey, t])

  const handlePageChange = useCallback((_e: unknown, newPage: number) => {
    handleExecute(newPage)
  }, [handleExecute])

  const handlePageSizeChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setPageSize(parseInt(e.target.value, 10))
    setPage(0)
  }, [])

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      handleExecute()
    }
    if (e.key === 'Tab') {
      e.preventDefault()
      const ta = textareaRef.current
      if (ta) {
        const start = ta.selectionStart
        const end = ta.selectionEnd
        setSql(prev => prev.substring(0, start) + '  ' + prev.substring(end))
        setTimeout(() => { ta.selectionStart = ta.selectionEnd = start + 2 }, 0)
      }
    }
  }, [handleExecute])

  const copyAsCsv = useCallback(() => {
    if (!result) return
    const header = result.columns.join(',')
    const rows = result.rows.map(r => r.map(v => v === null ? '' : String(v).includes(',') ? `"${v}"` : String(v)).join(','))
    copyToClipboard([header, ...rows].join('\n')).then(() => notify(t('database.query.csvCopied'), 'success'))
  }, [result, notify, t])

  const downloadCsv = useCallback(() => {
    if (!result) return
    const header = result.columns.join(',')
    const rows = result.rows.map(r => r.map(v => v === null ? '' : String(v).includes(',') ? `"${v}"` : String(v)).join(','))
    const csv = [header, ...rows].join('\n')
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${dbName}_query_results.csv`
    a.click()
    URL.revokeObjectURL(url)
  }, [result, dbName])

  const handleClose = () => {
    abortRef.current?.abort()
    abortRef.current = null
    setLoading(false)
    onClose()
    // Don't clear SQL — user might reopen
  }

  const history = open ? getHistory(dbKey) : []

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="lg" fullWidth fullScreen={fullScreen}>
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Code sx={{ mr: 1 }} />
        <Box sx={{ flex: 1 }}>
          {t('database.query.title')}
          <Typography component="span" variant="body2" sx={{ ml: 1, opacity: 0.8 }}>{dbName}</Typography>
        </Box>
        <FullscreenToggleButton fullScreen={fullScreen} onToggle={() => setFullScreen(f => !f)} color="white" />
        <IconButton onClick={handleClose} sx={{ color: 'white', ml: 1 }}><Close /></IconButton>
      </DialogTitle>
      <DialogContent sx={{ p: 0, display: 'flex', flexDirection: 'column', height: fullScreen ? 'calc(100vh - 64px)' : 600 }}>
        {/* SQL Editor */}
        <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
          <TextField
            inputRef={textareaRef}
            fullWidth
            multiline
            minRows={4}
            maxRows={fullScreen ? 12 : 6}
            value={sql}
            onChange={(e) => setSql(e.target.value)}
            placeholder={t('database.query.placeholder')}
            onKeyDown={handleKeyDown}
            slotProps={{ input: { sx: { fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' } } }}
            sx={{ mb: 1 }}
          />
          <Stack direction="row" spacing={1} alignItems="center">
            <Button
              variant="contained"
              startIcon={loading ? <CircularProgress size={16} color="inherit" /> : <PlayArrow />}
              onClick={() => handleExecute()}
              disabled={loading || !sql.trim()}
              size="small"
            >
              {t('database.query.execute')} (Ctrl+Enter)
            </Button>
            <Button
              variant="outlined"
              startIcon={<Speed />}
              onClick={() => handleExplain(false)}
              disabled={loading || !sql.trim()}
              size="small"
              color="info"
            >
              {t('database.query.explain')}
            </Button>
            <Tooltip title={t('database.query.explainAnalyzeTooltip')}>
              <Button
                variant="outlined"
                startIcon={<Speed />}
                onClick={() => handleExplain(true)}
                disabled={loading || !sql.trim()}
                size="small"
                color="warning"
              >
                {t('database.query.explainAnalyze')}
              </Button>
            </Tooltip>
            <Button size="small" startIcon={<Clear />} onClick={() => { setSql(''); setResult(null); setError('') }} disabled={loading}>
              {t('database.query.clear')}
            </Button>
            {history.length > 0 && (
              <>
                <Button size="small" startIcon={<History />} onClick={(e) => setHistoryAnchor(e.currentTarget)}>
                  {t('database.query.history')}
                </Button>
                <Menu anchorEl={historyAnchor} open={Boolean(historyAnchor)} onClose={() => setHistoryAnchor(null)}>
                  {history.map((h, i) => (
                    <MenuItem key={i} onClick={() => { setSql(h); setHistoryAnchor(null) }}
                      sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', maxWidth: 500 }}>
                      <Typography noWrap sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem' }}>{h}</Typography>
                    </MenuItem>
                  ))}
                </Menu>
              </>
            )}
            <Box sx={{ flex: 1 }} />
            <Typography variant="caption" color="text.secondary">
              {sql.length.toLocaleString()} / 102,400
            </Typography>
          </Stack>
          {needsPassword && (
            <TextField
              size="small"
              type="password"
              label={t('common.operationsPassword')}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="off"
              sx={{ mt: 1, maxWidth: 300 }}
              slotProps={{
                input: {
                  startAdornment: <InputAdornment position="start"><Lock sx={{ fontSize: 16 }} /></InputAdornment>,
                },
              }}
            />
          )}
        </Box>

        {/* Tabs */}
        {(result || planData) && (
          <Tabs value={activeTab} onChange={(_e, v) => setActiveTab(v)} sx={{ px: 2, borderBottom: 1, borderColor: 'divider', minHeight: 36 }}>
            <Tab label={t('database.query.tabResults')} sx={{ minHeight: 36, py: 0 }} />
            <Tab label={t('database.query.tabPlan')} icon={<AccountTree sx={{ fontSize: 16 }} />} iconPosition="start" sx={{ minHeight: 36, py: 0 }} disabled={!planData} />
          </Tabs>
        )}

        {/* Status / Error */}
        {error && (
          <Alert severity="error" sx={{ mx: 2, mt: 1, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
            {error}
          </Alert>
        )}
        {activeTab === 0 && result && !error && (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ px: 2, py: 1, borderBottom: 1, borderColor: 'divider' }}>
            <Chip
              label={result.queryType === 'SELECT'
                ? t('database.query.rowsReturned', { count: result.rows.length, total: result.totalRows >= 0 ? result.totalRows : '?' })
                : t('database.query.rowsAffected', { count: Number(result.rows[0]?.[0] ?? 0) })}
              size="small"
              color="success"
              variant="outlined"
            />
            <Chip label={`${result.executionTimeMs}ms`} size="small" variant="outlined" />
            <Box sx={{ flex: 1 }} />
            {result.queryType === 'SELECT' && result.rows.length > 0 && (
              <>
                <Tooltip title={t('database.query.copyCsv')}>
                  <IconButton size="small" onClick={copyAsCsv}><ContentCopy sx={{ fontSize: 16 }} /></IconButton>
                </Tooltip>
                <Tooltip title={t('database.query.downloadCsv')}>
                  <IconButton size="small" onClick={downloadCsv}><Download sx={{ fontSize: 16 }} /></IconButton>
                </Tooltip>
              </>
            )}
          </Stack>
        )}

        {/* Results Tab */}
        <Box sx={{ flex: 1, overflow: 'hidden', display: activeTab === 0 ? 'flex' : 'none', flexDirection: 'column' }}>
          {!result && !error && !loading && (
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: 'text.secondary' }}>
              <Typography variant="body2">{t('database.query.emptyState')}</Typography>
            </Box>
          )}
          {loading && (
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              <CircularProgress size={32} />
            </Box>
          )}
          {result && result.queryType === 'SELECT' && result.rows.length > 0 && (
            <ResultsTable result={result} />
          )}
          {result && result.queryType === 'SELECT' && result.rows.length === 0 && (
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: 'text.secondary' }}>
              <Typography variant="body2">{t('database.query.noResults')}</Typography>
            </Box>
          )}
        </Box>

        {/* Pagination */}
        {activeTab === 0 && result && result.queryType === 'SELECT' && result.totalRows !== 0 && (
          <TablePagination
            component="div"
            count={result.totalRows >= 0 ? result.totalRows : -1}
            page={page}
            onPageChange={handlePageChange}
            rowsPerPage={pageSize}
            onRowsPerPageChange={handlePageSizeChange}
            rowsPerPageOptions={[50, 100, 250, 500]}
            labelRowsPerPage={t('common.rowsPerPage')}
            sx={{ borderTop: 1, borderColor: 'divider' }}
            slotProps={{
              actions: { previousButton: { disabled: loading }, nextButton: { disabled: loading } },
              select: { disabled: loading },
            }}
          />
        )}

        {/* Plan Tab */}
        {activeTab === 1 && planData && (
          <Box sx={{ flex: 1, overflow: 'auto', p: 2 }}>
            <PlanTreeView plan={planData} tableStats={tableStats} />
          </Box>
        )}
      </DialogContent>
    </Dialog>
  )
}

// ---- Memoized Results Table ----

const ResultsTable = memo(function ResultsTable({ result }: { result: QueryResult }) {
  return (
    <TableContainer sx={{ flex: 1, overflow: 'auto' }}>
      <Table size="small" stickyHeader>
        <TableHead>
          <TableRow>
            <TableCell sx={ROW_NUM_HEADER_SX}>#</TableCell>
            {result.columns.map((col) => (
              <TableCell key={col} sx={COL_HEADER_SX}>{col}</TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {result.rows.map((row, i) => (
            <TableRow key={i} hover>
              <TableCell sx={ROW_NUM_CELL_SX}>
                {result.page * result.pageSize + i + 1}
              </TableCell>
              {row.map((val, j) => (
                <TableCell key={j} sx={val === null ? NULL_CELL_SX : DATA_CELL_SX}>
                  {val === null ? 'NULL' : String(val)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
})

// ---- Plan Tree Visualization ----

interface PlanNode {
  'Node Type': string
  'Relation Name'?: string
  'Index Name'?: string
  'Join Type'?: string
  'Filter'?: string
  'Index Cond'?: string
  'Sort Key'?: string[]
  'Startup Cost': number
  'Total Cost': number
  'Plan Rows': number
  'Plan Width': number
  'Actual Startup Time'?: number
  'Actual Total Time'?: number
  'Actual Rows'?: number
  'Actual Loops'?: number
  Plans?: PlanNode[]
  [key: string]: unknown
}

function PlanTreeView({ plan, tableStats }: { plan: PlanNode[] | { Plan: PlanNode }[]; tableStats: DatabaseTableStats | null }) {
  const root = Array.isArray(plan) && plan[0] ? (plan[0] as { Plan?: PlanNode }).Plan ?? plan[0] : null
  if (!root) return <Typography color="text.secondary">No plan data</Typography>

  const maxCost = (root as PlanNode)['Total Cost'] || 1
  const isAnalyze = 'Actual Total Time' in (root as PlanNode)

  // Extract summary
  const planningTime = Array.isArray(plan) && plan[0] ? (plan[0] as Record<string, unknown>)['Planning Time'] : null
  const executionTime = Array.isArray(plan) && plan[0] ? (plan[0] as Record<string, unknown>)['Execution Time'] : null

  return (
    <Stack spacing={1.5}>
      {/* Summary */}
      {(planningTime != null || executionTime != null) && (
        <Stack direction="row" spacing={1.5}>
          {planningTime != null && (
            <Chip label={`Planning: ${Number(planningTime).toFixed(2)}ms`} size="small" variant="outlined" />
          )}
          {executionTime != null && (
            <Chip label={`Execution: ${Number(executionTime).toFixed(2)}ms`} size="small" variant="outlined" color="primary" />
          )}
        </Stack>
      )}
      <PlanNodeCard node={root as PlanNode} depth={0} maxCost={maxCost} isAnalyze={isAnalyze} tableStats={tableStats} />
    </Stack>
  )
}

function formatBytesShort(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB'
}

function PlanNodeCard({ node, depth, maxCost, isAnalyze, tableStats }: { node: PlanNode; depth: number; maxCost: number; isAnalyze: boolean; tableStats: DatabaseTableStats | null }) {
  const costPct = maxCost > 0 ? (node['Total Cost'] / maxCost) * 100 : 0
  const isSeqScan = node['Node Type'] === 'Seq Scan'
  const rowMismatch = isAnalyze && node['Actual Rows'] != null && node['Plan Rows'] > 0
    ? Math.abs(node['Actual Rows'] - node['Plan Rows']) / Math.max(node['Plan Rows'], 1)
    : 0

  const nodeColor = costPct > 80 ? 'error.main' : costPct > 40 ? 'warning.main' : 'success.main'

  return (
    <Box sx={{ ml: depth * 3 }}>
      <Paper variant="outlined" sx={{ p: 1.5, mb: 1, borderLeft: 3, borderColor: nodeColor }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
          <Typography variant="body2" fontWeight={700}>{node['Node Type']}</Typography>
          {node['Relation Name'] && (
            <Chip label={node['Relation Name']} size="small" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
          )}
          {node['Index Name'] && (
            <Chip label={node['Index Name']} size="small" color="info" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
          )}
          {node['Join Type'] && (
            <Chip label={node['Join Type']} size="small" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
          )}
          {isSeqScan && (
            <Tooltip title="Sequential scan — consider adding an index">
              <Warning sx={{ fontSize: 16, color: 'warning.main' }} />
            </Tooltip>
          )}
          {rowMismatch > 5 && (
            <Tooltip title={`Row estimate mismatch: planned ${node['Plan Rows']}, actual ${node['Actual Rows']}. Consider running ANALYZE on the table.`}>
              <ErrorOutline sx={{ fontSize: 16, color: 'error.main' }} />
            </Tooltip>
          )}
        </Stack>

        {/* Cost bar */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
          <LinearProgress variant="determinate" value={costPct} sx={{
            flex: 1, height: 6, borderRadius: 3, bgcolor: 'grey.200', maxWidth: 120,
            '& .MuiLinearProgress-bar': { borderRadius: 3, bgcolor: nodeColor },
          }} />
          <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', minWidth: 40 }}>
            {costPct.toFixed(0)}%
          </Typography>
        </Box>

        {/* Metrics */}
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
          <Typography variant="caption" color="text.secondary">
            cost: <strong>{node['Total Cost'].toFixed(2)}</strong>
          </Typography>
          <Typography variant="caption" color="text.secondary">
            rows: <strong>{node['Plan Rows']}</strong>
          </Typography>
          {isAnalyze && node['Actual Total Time'] != null && (
            <Typography variant="caption" color="text.secondary">
              actual time: <strong>{node['Actual Total Time'].toFixed(2)}ms</strong>
            </Typography>
          )}
          {isAnalyze && node['Actual Rows'] != null && (
            <Typography variant="caption" sx={{ color: rowMismatch > 5 ? 'error.main' : 'text.secondary' }}>
              actual rows: <strong>{node['Actual Rows']}</strong>
              {rowMismatch > 1 && ` (${rowMismatch > 1 ? '⚠' : ''} ${(rowMismatch * 100).toFixed(0)}% off)`}
            </Typography>
          )}
          {node['Actual Loops'] != null && node['Actual Loops'] > 1 && (
            <Typography variant="caption" color="text.secondary">
              loops: <strong>{node['Actual Loops']}</strong>
            </Typography>
          )}
        </Stack>

        {/* Filter/condition */}
        {node['Filter'] && (
          <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', display: 'block', mt: 0.5, color: 'text.secondary' }}>
            Filter: {String(node['Filter'])}
          </Typography>
        )}
        {node['Index Cond'] && (
          <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', display: 'block', mt: 0.5, color: 'text.secondary' }}>
            Index Cond: {String(node['Index Cond'])}
          </Typography>
        )}
        {node['Sort Key'] && (
          <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', display: 'block', mt: 0.5, color: 'text.secondary' }}>
            Sort: {Array.isArray(node['Sort Key']) ? node['Sort Key'].join(', ') : String(node['Sort Key'])}
          </Typography>
        )}

        {/* Table context — show related table info when available */}
        {node['Relation Name'] && tableStats && (() => {
          const tbl = tableStats.tables.find(t => t.tableName === node['Relation Name'])
          if (!tbl) return null
          const totalScans = tbl.seqScan + tbl.idxScan
          const idxRatio = totalScans > 0 ? (tbl.idxScan / totalScans * 100) : -1
          const deadRatio = (tbl.liveTuples + tbl.deadTuples) > 0 ? (tbl.deadTuples / (tbl.liveTuples + tbl.deadTuples) * 100) : 0
          const relatedIndexes = tableStats.usedIndexes.filter(idx => idx.tableName === node['Relation Name'])
          const unusedIndexes = tableStats.unusedIndexes.filter(idx => idx.tableName === node['Relation Name'])

          return (
            <Box sx={{ mt: 1, p: 1, bgcolor: 'action.hover', borderRadius: 1 }}>
              <Typography variant="caption" fontWeight={700} color="text.secondary" sx={{ display: 'block', mb: 0.5, textTransform: 'uppercase', fontSize: '0.6rem', letterSpacing: '0.05em' }}>
                Table: {node['Relation Name']}
              </Typography>
              <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', mb: 0.5 }}>
                <Typography variant="caption" color="text.secondary">
                  Size: <strong>{formatBytesShort(tbl.totalSizeBytes)}</strong> (data: {formatBytesShort(tbl.tableSizeBytes)}, idx: {formatBytesShort(tbl.indexSizeBytes)})
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Rows: <strong>{tbl.liveTuples.toLocaleString()}</strong>
                </Typography>
                {deadRatio > 5 && (
                  <Typography variant="caption" sx={{ color: 'error.main' }}>
                    Dead tuples: {tbl.deadTuples.toLocaleString()} ({deadRatio.toFixed(0)}%) — consider VACUUM
                  </Typography>
                )}
                {idxRatio >= 0 && (
                  <Typography variant="caption" sx={{ color: idxRatio >= 90 ? 'success.main' : idxRatio >= 50 ? 'warning.main' : 'error.main' }}>
                    Index usage: {idxRatio.toFixed(0)}%
                  </Typography>
                )}
              </Stack>
              {isSeqScan && relatedIndexes.length > 0 && (
                <Box sx={{ mt: 0.5 }}>
                  <Typography variant="caption" color="info.main" fontWeight={600} sx={{ display: 'block', mb: 0.25 }}>
                    Available indexes (consider using WHERE clause):
                  </Typography>
                  {relatedIndexes.map(idx => (
                    <Typography key={idx.indexName} variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.65rem', display: 'block', color: 'text.secondary' }}>
                      {idx.isPrimary ? '🔑' : idx.isUnique ? '🔒' : '📇'} {idx.indexName} ({idx.idxScan.toLocaleString()} scans, {formatBytesShort(idx.sizeBytes)})
                    </Typography>
                  ))}
                </Box>
              )}
              {isSeqScan && relatedIndexes.length === 0 && unusedIndexes.length === 0 && (
                <Alert severity="warning" variant="outlined" sx={{ mt: 0.5, py: 0, fontSize: '0.7rem' }}>
                  No indexes found on this table. Consider creating an index for frequently filtered columns.
                </Alert>
              )}
              {unusedIndexes.length > 0 && (
                <Box sx={{ mt: 0.5 }}>
                  <Typography variant="caption" color="warning.main" fontWeight={600} sx={{ display: 'block', mb: 0.25 }}>
                    {unusedIndexes.length} unused index(es) ({formatBytesShort(unusedIndexes.reduce((s, i) => s + i.sizeBytes, 0))} wasted):
                  </Typography>
                  {unusedIndexes.map(idx => (
                    <Typography key={idx.indexName} variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.65rem', display: 'block', color: 'text.secondary' }}>
                      ⚠ {idx.indexName} ({formatBytesShort(idx.sizeBytes)}) — never scanned, consider dropping or adjusting your WHERE clause to use it
                    </Typography>
                  ))}
                </Box>
              )}
            </Box>
          )
        })()}
      </Paper>

      {/* Children */}
      {node.Plans?.map((child, i) => (
        <PlanNodeCard key={i} node={child} depth={depth + 1} maxCost={maxCost} isAnalyze={isAnalyze} tableStats={tableStats} />
      ))}
    </Box>
  )
}
