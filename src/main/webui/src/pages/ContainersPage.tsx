import { useState, useEffect, useCallback, useMemo } from 'react'
import dayjs from 'dayjs'
import customParseFormat from 'dayjs/plugin/customParseFormat'
import type { DockerContainer } from '../types'
import {
  getContainers,
  getAllowedRepositories,
  isDatabaseListingEnabled,
  isMigrationEnabled as checkMigrationEnabled,
  getMigratedDatabases,
  getFeatures,
  getMemoryStatus,
  type HostMemoryStatus,
  type MigratedDatabase,
} from '../services/containerService'

dayjs.extend(customParseFormat)
import { isDumpEnabled, getActiveRestores, type ActiveRestore } from '../services/dumpService'
import NewContainerModal from '../components/NewContainerModal'
import RunMigrationModal from '../components/RunMigrationModal'
import QuickScheduleDialog from '../components/QuickScheduleDialog'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import ContainerLogsDialog from '../components/ContainerLogsDialog'
import ContainerStatsDialog from '../components/ContainerStatsDialog'
import ContainerTerminalDialog from '../components/ContainerTerminalDialog'
import PasswordConfirmDialog from '../components/PasswordConfirmDialog'
import ExpirationChip from '../components/ExpirationChip'
import OperationProgress, { REMOVE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useContainerActions } from '../hooks/useContainerActions'
import { useContainerDialogs } from '../hooks/useContainerDialogs'
import { useTerminalAuth } from '../hooks/useTerminalAuth'
import { useActionMenu } from '../hooks/useActionMenu'
import { useTablePagination } from '../hooks/useTablePagination'
import {
  Box,
  Typography,
  Button,
  TextField,
  InputAdornment,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TableSortLabel,
  Paper,
  Chip,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  IconButton,
  Tooltip,
  Menu,
  MenuItem,
  ListItemIcon,
  ListItemText,
  Divider,
  FormControlLabel,
  Checkbox,
  Switch,
  Slider,
  Alert,
  AlertTitle,
  TablePagination,
  LinearProgress,
} from '@mui/material'
import { Search, AddCircleOutline, Stop, PlayArrow, Delete, ViewColumn, Warning, MoreTime, CameraAlt, Terminal, Dns, CheckCircle, StopCircle, Schedule, SwapHoriz, AccessTime, Monitor, MoreVert, CleaningServices, FiberManualRecord, Code, Memory } from '@mui/icons-material'
import { isSchedulingEnabled, listSchedules } from '../services/scheduleService'
import { subscribeContainerUpdates } from '../services/sseService'
import type { ContainerSchedule } from '../types'

interface ColumnDef {
  key: string
  label: string
  defaultVisible: boolean
}

const STORAGE_KEY = 'containerColumnsVisibility'

import { DAY_MARKS } from '../utils/constants'

function loadVisibility(columns: ColumnDef[]): Record<string, boolean> {
  const defaults = Object.fromEntries(columns.map((c) => [c.key, c.defaultVisible]))
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      const parsed = JSON.parse(stored) as Record<string, boolean>
      // Merge: use stored value for known columns, defaults for new ones, ignore removed ones
      return Object.fromEntries(columns.map((c) => [c.key, c.key in parsed ? parsed[c.key] : c.defaultVisible]))
    }
  } catch { /* ignore */ }
  return defaults
}

export default function ContainersPage() {
  const { notify, confirm } = useNotification()
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx, theadCheckboxSx } = useTableHeaderTheme()

  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasRepos, setHasRepos] = useState(false)
  const [dbListingEnabled, setDbListingEnabled] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [columnMenuAnchor, setColumnMenuAnchor] = useState<null | HTMLElement>(null)
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [activeRestores, setActiveRestores] = useState<ActiveRestore[]>([])
  const [dumpEnabled, setDumpEnabled] = useState(false)
  const [migratedDatabases, setMigratedDatabases] = useState<MigratedDatabase[]>([])
  const [migrationFeatureEnabled, setMigrationFeatureEnabled] = useState(false)
  const [terminalFeatureEnabled, setTerminalFeatureEnabled] = useState(false)
  const [terminalPasswordRequired, setTerminalPasswordRequired] = useState(true)
  const [schedulingPwRequired, setSchedulingPwRequired] = useState(true)
  const [schedulingFeatureEnabled, setSchedulingFeatureEnabled] = useState(false)
  const [containerSchedules, setContainerSchedules] = useState<Map<string, ContainerSchedule[]>>(new Map())
  const [showStoppedOnly, setShowStoppedOnly] = useState(false)
  const [memoryGuardEnabled, setMemoryGuardEnabled] = useState(false)
  const [memoryStatus, setMemoryStatus] = useState<HostMemoryStatus | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [remoteLockIds, setRemoteLockIds] = useState<Set<string>>(new Set())

  // Extracted hooks
  const loadContainers = useCallback(() => {
    setLoading(true)
    getContainers()
      .then((data) => { setContainers(data); setSelected(new Set()) })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const refreshMemoryStatus = useCallback(() => {
    if (memoryGuardEnabled) getMemoryStatus().then(setMemoryStatus).catch(() => {})
  }, [memoryGuardEnabled])

  const actions = useContainerActions({ notify, confirm, t, loadContainers })
  const dialogs = useContainerDialogs()
  const terminal = useTerminalAuth({ notify, t })
  const actionMenu = useActionMenu<DockerContainer>()

  const isUp = (status: string) => status.includes('Up')
  const machineIp = window.location.hostname

  const toggleSelect = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }, [])

  const BASE_COLUMNS: ColumnDef[] = useMemo(() => [
    { key: 'names', label: t('containers.columns.name'), defaultVisible: true },
    { key: 'status', label: t('containers.columns.status'), defaultVisible: true },
    { key: 'image', label: t('containers.columns.image'), defaultVisible: true },
    { key: 'tag', label: t('containers.columns.tag'), defaultVisible: true },
    { key: 'ports', label: t('containers.columns.ports'), defaultVisible: true },
    { key: 'ipAddress', label: t('containers.columns.ipAddress'), defaultVisible: false },
    { key: 'database', label: t('containers.columns.database'), defaultVisible: true },
    { key: 'expires', label: t('containers.columns.expires'), defaultVisible: true },
    { key: 'created', label: t('containers.columns.created'), defaultVisible: true },
    { key: 'containerId', label: t('containers.columns.containerId'), defaultVisible: false },
    { key: 'command', label: t('containers.columns.command'), defaultVisible: false },
    { key: 'actions', label: t('containers.columns.actions'), defaultVisible: true },
  ], [t])

  const columns = useMemo(() =>
    dbListingEnabled ? BASE_COLUMNS : BASE_COLUMNS.filter((c) => c.key !== 'database'),
  [dbListingEnabled, BASE_COLUMNS])

  const [columnVisibility, setColumnVisibility] = useState<Record<string, boolean>>(() => loadVisibility(BASE_COLUMNS))
  const visibleColumns = columns.filter((c) => columnVisibility[c.key])
  const vis = new Set(visibleColumns.map((c) => c.key))
  const colSpan = visibleColumns.length + 1 // +1 for checkbox column

  function toggleColumn(key: string) {
    setColumnVisibility((prev) => {
      const updated = { ...prev, [key]: !prev[key] }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
      return updated
    })
  }

  function loadContainerSchedules() {
    listSchedules().then((all) => {
      const map = new Map<string, ContainerSchedule[]>()
      for (const s of all) {
        if (s.containerId && s.enabled) {
          const list = map.get(s.containerId) || []
          list.push(s)
          map.set(s.containerId, list)
        }
      }
      setContainerSchedules(map)
    }).catch(() => setContainerSchedules(new Map()))
  }

  useEffect(() => {
    loadContainers()
    getAllowedRepositories().then((repos) => setHasRepos(repos.length > 0)).catch(() => {})
    isDatabaseListingEnabled().then(setDbListingEnabled).catch(() => {})
    isDumpEnabled().then(setDumpEnabled).catch(() => {})
    getMigratedDatabases().then(setMigratedDatabases).catch(() => setMigratedDatabases([]))
    checkMigrationEnabled().then(setMigrationFeatureEnabled).catch(() => setMigrationFeatureEnabled(false))
    getFeatures().then((f) => {
      setTerminalFeatureEnabled(f.terminal)
      setTerminalPasswordRequired(f.terminalPasswordRequired)
      setSchedulingPwRequired(f.schedulingPasswordRequired)
      setMemoryGuardEnabled(f.memoryGuard)
    }).catch(() => setTerminalFeatureEnabled(false))
    isSchedulingEnabled().then((enabled) => {
      setSchedulingFeatureEnabled(enabled)
      if (enabled) loadContainerSchedules()
    }).catch(() => setSchedulingFeatureEnabled(false))
  }, [loadContainers])

  useEffect(() => subscribeContainerUpdates(
    loadContainers,
    (ids) => setRemoteLockIds(prev => new Set([...prev, ...ids])),
    (ids) => setRemoteLockIds(prev => {
      const next = new Set(prev)
      ids.forEach(id => next.delete(id))
      return next
    }),
  ), [loadContainers])

  useEffect(() => { refreshMemoryStatus() }, [refreshMemoryStatus, containers])

  useEffect(() => {
    const check = () => getActiveRestores().then(setActiveRestores).catch(() => setActiveRestores([]))
    check()
    const interval = setInterval(check, 3000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    const now = Date.now()
    const expirations = containers
      .filter((c) => c.expiresAt)
      .map((c) => new Date(c.expiresAt!).getTime())
    if (expirations.length === 0) return

    const nearest = Math.min(...expirations)
    const fireAt = nearest > now ? nearest + 6000 : now + 6000
    const delay = fireAt - now

    const timer = setTimeout(() => {
      getContainers()
        .then(setContainers)
        .catch(() => {})
    }, delay)
    return () => clearTimeout(timer)
  }, [containers])

  const cleanupCandidates = useMemo(
    () => containers.filter(c => !isUp(c.status) && getContainerIdleDays(c) >= dialogs.cleanup.minDays),
    [containers, dialogs.cleanup.minDays]
  )

  function handleSort(key: string) {
    if (key === 'actions') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  function getContainerValue(c: DockerContainer, key: string): string {
    switch (key) {
      case 'containerId': return c.containerId
      case 'image': return c.image
      case 'tag': return c.image.split(':')[1] ?? ''
      case 'command': return c.command
      case 'created': return c.created
      case 'status': return c.status
      case 'ports': return c.ports
      case 'ipAddress': return c.ipAddress ?? ''
      case 'names': return c.names
      case 'database': return c.databaseName ?? ''
      case 'expires': return c.expiresAt ?? ''
      default: return ''
    }
  }

  function getContainerIdleDays(c: DockerContainer): number {
    // Parse idle time from Docker status string, e.g. "Exited (0) 3 days ago"
    const match = c.status.match(/Exited\s*\(\d+\)\s+(.+)\s+ago/)
    if (match) {
      const elapsed = match[1].toLowerCase()
      const num = parseInt(elapsed, 10) || 0
      if (elapsed.includes('second') || elapsed.includes('minute') || elapsed.includes('hour')) return 0
      if (elapsed.includes('day')) return num
      if (elapsed.includes('week')) return num * 7
      if (elapsed.includes('month')) return num * 30
      if (elapsed.includes('year')) return num * 365
    }
    // Fallback: use creation date
    const created = dayjs(c.created, 'DD/MM/YYYY HH:mm:ss')
    if (!created.isValid()) return 0
    return dayjs().diff(created, 'day')
  }

  const filteredByStatus = useMemo(
    () => showStoppedOnly ? containers.filter(c => !isUp(c.status)) : containers,
    [containers, showStoppedOnly]
  )

  const filtered = useMemo(() => {
    const result = filteredByStatus.filter((c) =>
      Object.values(c).some((v) => String(v ?? '').toLowerCase().includes(filter.toLowerCase()))
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      const va = getContainerValue(a, sortKey).toLowerCase()
      const vb = getContainerValue(b, sortKey).toLowerCase()
      const cmp = va.localeCompare(vb)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [filteredByStatus, filter, sortKey, sortDir])

  const toggleSelectAll = useCallback(() => {
    setSelected(prev =>
      prev.size === filtered.length ? new Set() : new Set(filtered.map(c => c.containerId))
    )
  }, [filtered])

  const selectedContainers = useMemo(
    () => filtered.filter(c => selected.has(c.containerId)),
    [filtered, selected],
  )

  const pagination = useTablePagination(filtered, { storageKey: 'containers' })

  const dbsScheduledForDeletion = useMemo(() => {
    const map = new Map<string, string>()
    for (const c of containers) {
      if (c.databaseName && c.deleteDatabaseOnExpiration) {
        map.set(c.databaseName, c.names)
      }
    }
    return map
  }, [containers])

  const runningCount = useMemo(() => containers.filter(c => isUp(c.status)).length, [containers])
  const stoppedCount = useMemo(() => containers.filter(c => !isUp(c.status)).length, [containers])
  const expiringCount = useMemo(() => containers.filter(c => c.expiresAt).length, [containers])

  return (
    <>
      <HeroBanner linkTo="/images" linkLabel={t('hero.exploreImages')} />

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" fontWeight="bold">{t('containers.title')}</Typography>
          {hasRepos && (
            <Button
              variant="contained"
              color="success"
              startIcon={<AddCircleOutline />}
              onClick={() => setModalOpen(true)}
            >
              {t('containers.newContainer')}
            </Button>
          )}
        </Box>

        {!loading && containers.length > 0 && (
          <Paper elevation={2} sx={{ p: 2.5, mb: 3, borderRadius: 2 }}>
            <Box sx={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'space-around' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Dns color="primary" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{containers.length}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('containers.overview.total')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <CheckCircle color="success" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{runningCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('containers.overview.running')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <StopCircle color={stoppedCount > 0 ? 'error' : 'disabled'} sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{stoppedCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('containers.overview.stopped')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Schedule color={expiringCount > 0 ? 'warning' : 'disabled'} sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{expiringCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('containers.overview.expiring')}</Typography>
                </Box>
              </Box>
              {memoryGuardEnabled && memoryStatus?.supported && (
                <Tooltip title={t('containers.overview.memoryTooltip', { available: memoryStatus.availableMb, threshold: memoryStatus.thresholdMb })} arrow>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, cursor: 'default' }}>
                    <Memory color={!memoryStatus.available ? 'error' : memoryStatus.availableMb < memoryStatus.thresholdMb * 2 ? 'warning' : 'success'} sx={{ fontSize: 32 }} />
                    <Box>
                      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace", fontSize: '1.1rem' }}>
                        {(memoryStatus.usedMb / 1024).toFixed(1)} / {(memoryStatus.totalMb / 1024).toFixed(1)} GB
                      </Typography>
                      <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('containers.overview.memory')}</Typography>
                    </Box>
                  </Box>
                </Tooltip>
              )}
            </Box>
          </Paper>
        )}

        {activeRestores.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>{t('containers.restoreInProgress')}</AlertTitle>
            {activeRestores.map((r, i) => (
              <Typography key={i} variant="body2">
                <span dangerouslySetInnerHTML={{ __html: t('containers.restoringInto', { filename: r.dumpFilename, database: r.targetDatabase, repository: r.repository }) }} />
              </Typography>
            ))}
          </Alert>
        )}

        <Box sx={{ display: 'flex', gap: 2, mb: 2, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            placeholder={t('containers.searchPlaceholder')}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            size="small"
            variant="outlined"
            sx={{ flex: 1, minWidth: 200 }}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <Search color="action" />
                  </InputAdornment>
                ),
              },
            }}
          />
          <FormControlLabel
            control={
              <Switch
                checked={showStoppedOnly}
                onChange={(e) => setShowStoppedOnly(e.target.checked)}
                size="small"
              />
            }
            label={
              <Typography variant="body2">
                {t('containers.showStoppedOnly')} ({stoppedCount})
              </Typography>
            }
          />
          {selected.size > 0 && (
            <>
              <Button
                variant="contained"
                color="success"
                startIcon={<PlayArrow />}
                onClick={() => actions.handleBulkStart(selectedContainers)}
                disabled={!!actions.bulkProgress || selectedContainers.every(c => isUp(c.status))}
                size="small"
              >
                {t('containers.start')} ({selectedContainers.filter(c => !isUp(c.status)).length})
              </Button>
              <Button
                variant="contained"
                color="warning"
                startIcon={<Stop />}
                onClick={() => actions.handleBulkStop(selectedContainers)}
                disabled={!!actions.bulkProgress || selectedContainers.every(c => !isUp(c.status))}
                size="small"
              >
                {t('containers.stop')} ({selectedContainers.filter(c => isUp(c.status)).length})
              </Button>
              <Button
                variant="contained"
                color="error"
                startIcon={<Delete />}
                onClick={() => actions.handleBulkRemove(selectedContainers)}
                disabled={!!actions.bulkProgress}
                size="small"
              >
                {t('common.delete')} ({selected.size})
              </Button>
            </>
          )}
          <Tooltip title={t('containers.cleanup.description')}>
            <span>
              <Button
                variant="contained"
                color="warning"
                startIcon={<CleaningServices />}
                onClick={dialogs.openCleanup}
                disabled={stoppedCount === 0}
                size="small"
              >
                {t('containers.cleanup.button')}
              </Button>
            </span>
          </Tooltip>
          <Tooltip title={t('containers.toggleColumns')}>
            <IconButton onClick={(e) => setColumnMenuAnchor(e.currentTarget)} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
              <ViewColumn />
            </IconButton>
          </Tooltip>
          <Menu
            anchorEl={columnMenuAnchor}
            open={Boolean(columnMenuAnchor)}
            onClose={() => setColumnMenuAnchor(null)}
          >
            <Box sx={{ px: 2, py: 1 }}>
              {columns.map((col) => (
                <FormControlLabel
                  key={col.key}
                  control={
                    <Checkbox
                      checked={columnVisibility[col.key] ?? col.defaultVisible}
                      onChange={() => toggleColumn(col.key)}
                      size="small"
                    />
                  }
                  label={col.label}
                  sx={{ display: 'block' }}
                />
              ))}
            </Box>
          </Menu>
        </Box>

        {actions.bulkProgress && (
          <Box sx={{ mb: 1 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
              {actions.bulkProgress.action} {actions.bulkProgress.current}/{actions.bulkProgress.total}...
            </Typography>
            <LinearProgress
              variant="determinate"
              value={(actions.bulkProgress.current / actions.bulkProgress.total) * 100}
            />
          </Box>
        )}

        <Paper elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
          <Table stickyHeader aria-label="Containers" size="small">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                  <Checkbox
                    size="small"
                    checked={filtered.length > 0 && selected.size === filtered.length}
                    indeterminate={selected.size > 0 && selected.size < filtered.length}
                    onChange={toggleSelectAll}
                    sx={theadCheckboxSx}
                  />
                </TableCell>
                {visibleColumns.map((col) => (
                  <TableCell key={col.key} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>
                    {col.key !== 'actions' ? (
                      <TableSortLabel
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                        onClick={() => handleSort(col.key)}
                        sx={theadSortSx}
                      >
                        {col.label}
                      </TableSortLabel>
                    ) : col.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={colSpan} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={colSpan} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {t('containers.noContainersFound')}
                  </TableCell>
                </TableRow>
              )}
              {pagination.paginatedData.map((c) => {
                const isBulkOperating = actions.bulkOperatingIds.has(c.containerId) || remoteLockIds.has(c.containerId)
                return (
                <TableRow
                  key={c.containerId}
                  hover={!isBulkOperating}
                  selected={selected.has(c.containerId)}
                  sx={isBulkOperating ? { opacity: 0.5 } : undefined}
                  onContextMenu={isBulkOperating ? undefined : (e) => {
                    e.preventDefault()
                    actionMenu.openByPosition({ top: e.clientY, left: e.clientX }, c)
                  }}
                >
                  <TableCell padding="checkbox">
                    {isBulkOperating
                      ? <CircularProgress size={20} />
                      : <Checkbox size="small" checked={selected.has(c.containerId)} onChange={() => toggleSelect(c.containerId)} />
                    }
                  </TableCell>
                  {vis.has('names') && (
                    <TableCell sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                        {c.names}
                        {schedulingFeatureEnabled && containerSchedules.has(c.containerId) && (() => {
                          const schedules = containerSchedules.get(c.containerId)!
                          const summary = schedules.map(s => `${s.name} (${s.action})`).join(', ')
                          return (
                            <Tooltip title={t('containers.activeSchedules', { count: schedules.length, summary })}>
                              <Chip
                                icon={<AccessTime />}
                                label={schedules.length}
                                size="small"
                                color="info"
                                variant="outlined"
                                sx={{ height: 20, fontSize: '0.7rem', '& .MuiChip-icon': { fontSize: 14 } }}
                                onClick={() => dialogs.openSchedule(c)}
                              />
                            </Tooltip>
                          )
                        })()}
                      </Box>
                    </TableCell>
                  )}
                  {vis.has('status') && (
                    <TableCell>
                      <Chip
                        icon={
                          <FiberManualRecord
                            sx={{
                              fontSize: 10,
                              ...(isUp(c.status) && {
                                animation: 'pulse 2s ease-in-out infinite',
                                '@keyframes pulse': {
                                  '0%, 100%': { opacity: 1 },
                                  '50%': { opacity: 0.4 },
                                },
                              }),
                            }}
                          />
                        }
                        label={c.status}
                        size="small"
                        color={isUp(c.status) ? 'success' : 'error'}
                        variant="outlined"
                        sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', height: 24 }}
                      />
                    </TableCell>
                  )}
                  {vis.has('image') &&<TableCell sx={{ fontSize: '0.85rem' }}>{c.image.split(':')[0]}</TableCell>}
                  {vis.has('tag') &&<TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{c.image.split(':')[1] ?? '-'}</TableCell>}
                  {vis.has('ports') && (
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                        {c.ports !== '-'
                          ? c.ports.split(',').map((port, i) => (
                              <Chip
                                key={i}
                                label={port.trim()}
                                size="small"
                                variant="outlined"
                                color="primary"
                                component="a"
                                href={`http://${machineIp}:${port.trim()}${c.portPaths?.[port.trim()] ?? ''}`}
                                target="_blank"
                                rel="noopener noreferrer"
                                clickable
                                sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', height: 24 }}
                              />
                            ))
                          : <Typography variant="body2" color="text.secondary">-</Typography>}
                      </Box>
                    </TableCell>
                  )}
                  {vis.has('ipAddress') && (
                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      {c.ipAddress || <Typography variant="body2" color="text.secondary">-</Typography>}
                    </TableCell>
                  )}
                  {vis.has('database') && (
                    <TableCell>
                      {c.databaseName ? (() => {
                        const migration = migratedDatabases.find(
                          (m) => m.databaseName === c.databaseName
                        )
                        return (
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <Typography variant="body2">{c.databaseName}</Typography>
                            {migration && (
                              <Tooltip title={
                                migration.mode === 'API' && migration.sourceVersion && migration.targetVersion
                                  ? t('containers.dbMigrated') + ` (${migration.sourceVersion} \u2192 ${migration.targetVersion})`
                                  : t('containers.dbMigrated')
                              }>
                                <SwapHoriz sx={{ fontSize: 16, color: 'info.main' }} />
                              </Tooltip>
                            )}
                          </Box>
                        )
                      })() : (
                        <Typography variant="body2" color="text.secondary">-</Typography>
                      )}
                    </TableCell>
                  )}
                  {vis.has('expires') && (
                    <TableCell>
                      {c.expiresAt ? (
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <ExpirationChip expiresAt={c.expiresAt} onCancel={() => actions.handleCancelExpiration(c.containerId, c.names)} onExpired={loadContainers} />
                            <Tooltip title={t('containers.extendBy10')}>
                              <IconButton size="small" onClick={() => actions.handleExtendExpiration(c.containerId)} sx={{ p: 0.25 }}>
                                <MoreTime fontSize="small" />
                              </IconButton>
                            </Tooltip>
                          </Box>
                          {c.databaseName && c.deleteDatabaseOnExpiration && (
                            <Tooltip title={t('containers.dbWillBeDeleted', { database: c.databaseName })}>
                              <Chip
                                label={`DB: ${c.databaseName}`}
                                size="small"
                                color="warning"
                                icon={<Warning />}
                                variant="filled"
                                onDelete={() => actions.handleCancelDbDeletion(c.containerId, c.names)}
                              />
                            </Tooltip>
                          )}
                          {c.databaseName && !c.deleteDatabaseOnExpiration && dbsScheduledForDeletion.has(c.databaseName) && (
                            <Tooltip title={t('containers.dbScheduledByContainer', { database: c.databaseName, container: dbsScheduledForDeletion.get(c.databaseName) })}>
                              <Chip
                                label={`DB: ${c.databaseName}`}
                                size="small"
                                color="error"
                                icon={<Warning />}
                                variant="outlined"
                              />
                            </Tooltip>
                          )}
                        </Box>
                      ) : (
                        <Typography variant="body2" color="text.secondary">-</Typography>
                      )}
                    </TableCell>
                  )}
                  {vis.has('created') &&<TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>{formatBackendDate(c.created)}</TableCell>}
                  {vis.has('containerId') &&<TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>{c.containerId}</TableCell>}
                  {vis.has('command') &&<TableCell>{c.command}</TableCell>}
                  {vis.has('actions') && (
                    <TableCell>
                      {isBulkOperating ? (
                        <Tooltip title={t('containers.logs.title', { name: c.names })}>
                          <IconButton size="small" onClick={() => dialogs.openLogs(c)} sx={{ opacity: 1 }}>
                            <Monitor />
                          </IconButton>
                        </Tooltip>
                      ) : (
                        <Box sx={{ display: 'flex', gap: 0.25, alignItems: 'center' }}>
                          {isUp(c.status) ? (
                            <Tooltip title={actions.stoppingId === c.containerId ? t('containers.stopping') : t('containers.stop')}>
                              <span>
                                <IconButton
                                  size="small"
                                  color="warning"
                                  onClick={() => actions.handleStop(c.containerId, c.names)}
                                  disabled={actions.stoppingId === c.containerId}
                                >
                                  {actions.stoppingId === c.containerId ? <CircularProgress size={18} color="inherit" /> : <Stop />}
                                </IconButton>
                              </span>
                            </Tooltip>
                          ) : (
                            <Tooltip title={t('containers.start')}>
                              <IconButton
                                size="small"
                                color="primary"
                                onClick={() => actions.handleStart(c.containerId, c.names)}
                              >
                                <PlayArrow />
                              </IconButton>
                            </Tooltip>
                          )}
                          <IconButton
                            size="small"
                            onClick={(e) => actionMenu.openByAnchor(e.currentTarget, c)}
                          >
                            <MoreVert />
                          </IconButton>
                        </Box>
                      )}
                    </TableCell>
                  )}
                </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </Paper>
        <TablePagination
          component="div"
          count={pagination.totalCount}
          page={pagination.page}
          onPageChange={pagination.handleChangePage}
          rowsPerPage={pagination.rowsPerPage}
          onRowsPerPageChange={pagination.handleChangeRowsPerPage}
          rowsPerPageOptions={[10, 25, 50, 100]}
          labelRowsPerPage={t('common.rowsPerPage')}
        />

        <Menu
          anchorEl={actionMenu.anchorEl}
          open={actionMenu.menuOpen && actionMenu.target !== null}
          onClose={actionMenu.close}
          {...(actionMenu.contextMenuPos && !actionMenu.anchorEl ? {
            anchorReference: 'anchorPosition' as const,
            anchorPosition: actionMenu.contextMenuPos,
          } : {
            transformOrigin: { horizontal: 'right', vertical: 'top' },
            anchorOrigin: { horizontal: 'right', vertical: 'bottom' },
          })}
          slotProps={{ ...actionMenu.menuSlotProps, paper: { sx: { minWidth: 200 } } }}
        >
          {actionMenu.target && [
            isUp(actionMenu.target.status) ? (
              <MenuItem
                key="stop"
                onClick={() => {
                  if (!actionMenu.target) return
                  actions.handleStop(actionMenu.target.containerId, actionMenu.target.names)
                  actionMenu.close()
                }}
                disabled={actions.stoppingId === actionMenu.target?.containerId}
              >
                <ListItemIcon><Stop fontSize="small" color="warning" /></ListItemIcon>
                <ListItemText>{t('containers.stop')}</ListItemText>
              </MenuItem>
            ) : (
              <MenuItem
                key="start"
                onClick={() => {
                  if (!actionMenu.target) return
                  actions.handleStart(actionMenu.target.containerId, actionMenu.target.names)
                  actionMenu.close()
                }}
              >
                <ListItemIcon><PlayArrow fontSize="small" color="primary" /></ListItemIcon>
                <ListItemText>{t('containers.start')}</ListItemText>
              </MenuItem>
            ),

            <Divider key="action-divider" />,

            <MenuItem
              key="logs"
              onClick={() => {
                if (!actionMenu.target) return
                dialogs.openLogs(actionMenu.target)
                actionMenu.close()
              }}
            >
              <ListItemIcon><Terminal fontSize="small" /></ListItemIcon>
              <ListItemText>{t('containers.logs.viewLogs')}</ListItemText>
            </MenuItem>,

            isUp(actionMenu.target.status) && (
              <MenuItem
                key="stats"
                onClick={() => {
                  dialogs.openStats(actionMenu.target!)
                  actionMenu.close()
                }}
              >
                <ListItemIcon><Monitor fontSize="small" /></ListItemIcon>
                <ListItemText>{t('containers.stats.viewStats')}</ListItemText>
              </MenuItem>
            ),

            terminalFeatureEnabled && isUp(actionMenu.target.status) && (
              <MenuItem
                key="terminal"
                onClick={() => {
                  const cId = actionMenu.target!.containerId
                  const cName = actionMenu.target!.names
                  actionMenu.close()
                  terminal.requestTerminal(cId, cName, terminalPasswordRequired)
                }}
              >
                <ListItemIcon><Code fontSize="small" /></ListItemIcon>
                <ListItemText>{t('containers.terminal.openTerminal')}</ListItemText>
              </MenuItem>
            ),

            (dumpEnabled && actionMenu.target.repository && actionMenu.target.databaseName) || (migrationFeatureEnabled && actionMenu.target.repository && actionMenu.target.databaseName)
              ? <Divider key="db-divider" />
              : null,

            dumpEnabled && actionMenu.target.repository && actionMenu.target.databaseName && (
              <Tooltip title={t('containers.snapshotDatabase', { database: actionMenu.target.databaseName })} placement="left" arrow>
                <MenuItem
                  key="snapshot"
                  onClick={() => {
                    if (!actionMenu.target) return
                    dialogs.openSnapshot(actionMenu.target)
                    actionMenu.close()
                  }}
                >
                  <ListItemIcon><CameraAlt fontSize="small" /></ListItemIcon>
                  <ListItemText>{t('containers.snapshot')}</ListItemText>
                </MenuItem>
              </Tooltip>
            ),

            migrationFeatureEnabled && actionMenu.target.repository && actionMenu.target.databaseName && (
              <MenuItem
                key="migration"
                onClick={() => {
                  dialogs.openMigration(actionMenu.target!)
                  actionMenu.close()
                }}
              >
                <ListItemIcon><SwapHoriz fontSize="small" /></ListItemIcon>
                <ListItemText>{t('containers.runMigration')}</ListItemText>
              </MenuItem>
            ),

            schedulingFeatureEnabled && (
              <MenuItem
                key="schedule"
                onClick={() => {
                  dialogs.openSchedule(actionMenu.target!)
                  actionMenu.close()
                }}
              >
                <ListItemIcon><AccessTime fontSize="small" /></ListItemIcon>
                <ListItemText>{t('schedules.title')}</ListItemText>
              </MenuItem>
            ),

            <Divider key="delete-divider" />,

            <MenuItem
              key="delete"
              onClick={() => {
                if (!actionMenu.target) return
                actions.handleRemove(actionMenu.target.containerId, actionMenu.target.names)
                actionMenu.close()
              }}
              sx={{ color: 'error.main' }}
            >
              <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
              <ListItemText>{t('common.remove')}</ListItemText>
            </MenuItem>,
          ]}
        </Menu>
      </Box>

      <NewContainerModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={loadContainers}
      />

      <Dialog open={actions.removeSse.events.length > 0} onClose={actions.handleRemoveDialogClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('containers.removingContainer')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={actions.removeSse.events} steps={REMOVE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(actions.removeSse.hasError || actions.removeSse.isDone) && (
            <Button onClick={actions.handleRemoveDialogClose} color="inherit">{t('common.close')}</Button>
          )}
        </DialogActions>
      </Dialog>

      <CreateSnapshotModal
        open={dialogs.snapshot.open}
        onClose={dialogs.closeSnapshot}
        onCreated={() => {}}
        initialRepository={dialogs.snapshot.repo}
        initialDatabase={dialogs.snapshot.db}
        containerName={dialogs.snapshot.containerName}
      />

      <ContainerLogsDialog
        open={dialogs.logs.containerId !== null}
        containerId={dialogs.logs.containerId ?? ''}
        containerName={dialogs.logs.containerName}
        onClose={dialogs.closeLogs}
      />

      <ContainerStatsDialog
        open={dialogs.stats.containerId !== null}
        containerId={dialogs.stats.containerId ?? ''}
        containerName={dialogs.stats.containerName}
        onClose={dialogs.closeStats}
      />

      <PasswordConfirmDialog
        open={terminal.authDialogOpen}
        title={t('containers.terminal.openTerminal')}
        message={t('containers.terminal.enterPassword')}
        confirmLabel={t('containers.terminal.connect')}
        loadingLabel={t('containers.terminal.connecting')}
        confirmColor="primary"
        icon={<Code />}
        onConfirm={terminal.confirmAuth}
        onClose={terminal.cancelAuth}
      />

      <ContainerTerminalDialog
        open={!!terminal.ticket}
        ticket={terminal.ticket}
        containerName={terminal.containerName}
        onClose={terminal.closeTerminal}
      />

      <RunMigrationModal
        open={dialogs.migration.open}
        repository={dialogs.migration.repo}
        databaseName={dialogs.migration.db}
        onClose={dialogs.closeMigration}
        onCompleted={() => {
          loadContainers()
          getMigratedDatabases().then(setMigratedDatabases).catch(() => {})
        }}
      />

      <Dialog open={dialogs.cleanup.open} onClose={() => !dialogs.cleanup.running && dialogs.closeCleanup()} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <CleaningServices sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('containers.cleanup.title')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Typography sx={{ mb: 2 }}>
            {t('containers.cleanup.description')}
          </Typography>

          <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
            {t('containers.cleanup.minDaysLabel')}
          </Typography>
          <Box sx={{ px: 2, mb: 3 }}>
            <Slider
              value={dialogs.cleanup.minDays}
              onChange={(_, v) => dialogs.setCleanupMinDays(v as number)}
              min={0}
              max={90}
              step={1}
              marks={DAY_MARKS}
              valueLabelDisplay="auto"
              valueLabelFormat={(v) => v === 0 ? t('containers.cleanup.allStopped') : t('containers.cleanup.daysValue', { count: v })}
            />
          </Box>

          <Alert severity="info" sx={{ mb: 2 }}>
            {t('containers.cleanup.matchCount', { count: cleanupCandidates.length })}
          </Alert>

          {cleanupCandidates.length > 0 && (
            <Box sx={{ maxHeight: 150, overflowY: 'auto', mb: 1 }}>
              {cleanupCandidates.map(c => (
                <Typography key={c.containerId} variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.25 }}>
                  {c.names} — {getContainerIdleDays(c) === 0 ? '<1' : getContainerIdleDays(c)}d
                </Typography>
              ))}
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={dialogs.closeCleanup} color="inherit" disabled={dialogs.cleanup.running}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={async () => {
              dialogs.setCleanupRunning(true)
              await actions.handleCleanup(cleanupCandidates)
              dialogs.setCleanupRunning(false)
              dialogs.closeCleanup()
            }}
            disabled={dialogs.cleanup.running || cleanupCandidates.length === 0}
            startIcon={dialogs.cleanup.running ? <CircularProgress size={20} /> : <CleaningServices />}
          >
            {dialogs.cleanup.running ? t('containers.cleanup.running') : t('containers.cleanup.confirm', { count: cleanupCandidates.length })}
          </Button>
        </DialogActions>
      </Dialog>

      <QuickScheduleDialog
        open={dialogs.schedule.open}
        containerId={dialogs.schedule.containerId}
        containerName={dialogs.schedule.containerName}
        expiresAt={dialogs.schedule.expiresAt}
        passwordRequired={schedulingPwRequired}
        onClose={() => {
          dialogs.closeSchedule()
          if (schedulingFeatureEnabled) loadContainerSchedules()
        }}
      />
    </>
  )
}
