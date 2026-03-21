import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import dayjs from 'dayjs'
import customParseFormat from 'dayjs/plugin/customParseFormat'
import type { DockerContainer } from '../types'
import {
  getContainers,
  stopContainer,
  startContainer,
  removeContainer,
  getAllowedRepositories,
  cancelDatabaseDeletion,
  cancelExpiration,
  extendExpiration,
  isDatabaseListingEnabled,
  isMigrationEnabled as checkMigrationEnabled,
  getMigratedDatabases,
  type MigratedDatabase,
} from '../services/containerService'

dayjs.extend(customParseFormat)
import { isDumpEnabled, getActiveRestores, type ActiveRestore } from '../services/dumpService'
import { streamRemoveContainer } from  '../services/sseService'
import NewContainerModal from '../components/NewContainerModal'
import RunMigrationModal from '../components/RunMigrationModal'
import QuickScheduleDialog from '../components/QuickScheduleDialog'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import ContainerLogsDialog from '../components/ContainerLogsDialog'
import ContainerStatsDialog from '../components/ContainerStatsDialog'
import OperationProgress, { REMOVE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useSseOperation } from '../hooks/useSseOperation'
import {
  Box,
  Typography,
  Button,
  TextField,
  InputAdornment,
  Table,
  TableBody,
  TableCell,
  TableContainer,
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
} from '@mui/material'
import { Search, AddCircleOutline, Stop, PlayArrow, Delete, Timer, ViewColumn, Warning, MoreTime, CameraAlt, Terminal, Dns, CheckCircle, StopCircle, Schedule, SwapHoriz, AccessTime, Monitor, MoreVert, CleaningServices, FiberManualRecord } from '@mui/icons-material'
import { isSchedulingEnabled, listSchedules } from '../services/scheduleService'
import type { ContainerSchedule } from '../types'

interface ColumnDef {
  key: string
  label: string
  defaultVisible: boolean
}

const STORAGE_KEY = 'containerColumnsVisibility'

const DAY_MARKS = [
  { value: 1, label: '1' },
  { value: 7, label: '7' },
  { value: 14, label: '14' },
  { value: 30, label: '30' },
  { value: 60, label: '60' },
  { value: 90, label: '90' },
]

function loadVisibility(columns: ColumnDef[]): Record<string, boolean> {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) return JSON.parse(stored)
  } catch { /* ignore */ }
  return Object.fromEntries(columns.map((c) => [c.key, c.defaultVisible]))
}

export default function ContainersPage() {
  const { notify, confirm } = useNotification()
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()
  const removeSse = useSseOperation()
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
  const [stoppingId, setStoppingId] = useState<string | null>(null)
  const [dumpEnabled, setDumpEnabled] = useState(false)
  const [snapshotOpen, setSnapshotOpen] = useState(false)
  const [snapshotRepo, setSnapshotRepo] = useState<string | undefined>(undefined)
  const [snapshotDb, setSnapshotDb] = useState<string | undefined>(undefined)
  const [snapshotContainerName, setSnapshotContainerName] = useState<string | undefined>(undefined)
  const [logsContainerId, setLogsContainerId] = useState<string | null>(null)
  const [logsContainerName, setLogsContainerName] = useState('')
  const [statsContainerId, setStatsContainerId] = useState<string | null>(null)
  const [statsContainerName, setStatsContainerName] = useState('')
  const [migratedDatabases, setMigratedDatabases] = useState<MigratedDatabase[]>([])
  const [migrationFeatureEnabled, setMigrationFeatureEnabled] = useState(false)
  const [migrationRepo, setMigrationRepo] = useState('')
  const [migrationDb, setMigrationDb] = useState('')
  const [migrationOpen, setMigrationOpen] = useState(false)
  const [schedulingFeatureEnabled, setSchedulingFeatureEnabled] = useState(false)
  const [containerSchedules, setContainerSchedules] = useState<Map<string, ContainerSchedule[]>>(new Map())
  const [scheduleContainerId, setScheduleContainerId] = useState('')
  const [scheduleContainerName, setScheduleContainerName] = useState('')
  const [scheduleExpiresAt, setScheduleExpiresAt] = useState<string | undefined>(undefined)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [actionMenuAnchor, setActionMenuAnchor] = useState<null | HTMLElement>(null)
  const [contextMenuPos, setContextMenuPos] = useState<{ top: number; left: number } | null>(null)
  const [actionMenuContainer, setActionMenuContainer] = useState<DockerContainer | null>(null)
  const [showStoppedOnly, setShowStoppedOnly] = useState(false)
  const [cleanupDialogOpen, setCleanupDialogOpen] = useState(false)
  const [cleanupMinDays, setCleanupMinDays] = useState(7)
  const [cleanupRunning, setCleanupRunning] = useState(false)

  const isUp = (status: string) => status.includes('Up')
  const machineIp = window.location.hostname

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
  const colSpan = visibleColumns.length

  function toggleColumn(key: string) {
    setColumnVisibility((prev) => {
      const updated = { ...prev, [key]: !prev[key] }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
      return updated
    })
  }

  const loadContainers = useCallback(() => {
    setLoading(true)
    getContainers()
      .then(setContainers)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

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
    isSchedulingEnabled().then((enabled) => {
      setSchedulingFeatureEnabled(enabled)
      if (enabled) loadContainerSchedules()
    }).catch(() => setSchedulingFeatureEnabled(false))
  }, [loadContainers])

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

  async function handleStop(id: string, name: string) {
    if (!(await confirm(t('containers.confirmStop', { name })))) return
    setStoppingId(id)
    try {
      const ok = await stopContainer(id)
      notify(ok ? t('containers.containerStopped') : t('containers.failedToStop'), ok ? 'success' : 'error')
    } catch {
      notify(t('containers.stopError'), 'error')
    } finally {
      setStoppingId(null)
    }
    loadContainers()
  }

  async function handleStart(id: string, name: string) {
    if (!(await confirm(t('containers.confirmStart', { name })))) return
    try {
      const ok = await startContainer(id)
      notify(ok ? t('containers.containerStarted') : t('containers.failedToStart'), ok ? 'success' : 'error')
    } catch {
      notify(t('containers.startError'), 'error')
    }
    loadContainers()
  }

  async function handleRemove(id: string, name: string) {
    if (!(await confirm(t('containers.confirmRemove', { name })))) return

    removeSse.start(
      (onEvent, onDone, onError) => streamRemoveContainer(id, onEvent, onDone, onError),
      () => {
        setTimeout(() => {
          removeSse.reset()
          notify(t('containers.containerRemoved'), 'success')
          loadContainers()
        }, 1500)
      },
      () => loadContainers(),
    )
  }

  function handleRemoveDialogClose() {
    removeSse.cleanup()
    removeSse.reset()
    loadContainers()
  }

  async function handleExtendExpiration(id: string) {
    try {
      const ok = await extendExpiration(id, 10)
      notify(ok ? t('containers.expirationExtended') : t('containers.failedToExtend'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }

  async function handleCancelExpiration(id: string, name: string) {
    if (!(await confirm(t('containers.cancelExpiration', { name })))) return
    try {
      const ok = await cancelExpiration(id)
      notify(ok ? t('containers.expirationCancelled') : t('containers.failedToCancelExpiration'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }

  async function handleCancelDbDeletion(id: string, name: string) {
    if (!(await confirm(t('containers.cancelDbDeletion', { name })))) return
    try {
      const ok = await cancelDatabaseDeletion(id)
      notify(ok ? t('containers.dbDeletionCancelled') : t('containers.failedToCancelDbDeletion'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }

  const cleanupCandidates = useMemo(
    () => containers.filter(c => !isUp(c.status) && getContainerAgeDays(c) >= cleanupMinDays),
    [containers, cleanupMinDays]
  )

  async function handleCleanup() {
    if (cleanupCandidates.length === 0) return
    setCleanupRunning(true)
    let removed = 0
    let failed = 0
    for (const c of cleanupCandidates) {
      try {
        const ok = await removeContainer(c.containerId)
        if (ok) removed++; else failed++
      } catch {
        failed++
      }
    }
    setCleanupRunning(false)
    setCleanupDialogOpen(false)
    notify(
      t('containers.cleanup.result', { removed, failed }),
      failed > 0 ? 'warning' : 'success'
    )
    loadContainers()
  }

  function handleSnapshot(c: DockerContainer) {
    setSnapshotRepo(c.repository ?? undefined)
    setSnapshotDb(c.databaseName ?? undefined)
    setSnapshotContainerName(c.names)
    setSnapshotOpen(true)
  }

  function handleViewLogs(c: DockerContainer) {
    setLogsContainerId(c.containerId)
    setLogsContainerName(c.names)
  }

  function handleLogsClose() {
    setLogsContainerId(null)
    setLogsContainerName('')
  }

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

  function getContainerAgeDays(c: DockerContainer): number {
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
          <Tooltip title={t('containers.cleanup.description')}>
            <span>
              <Button
                variant="contained"
                color="warning"
                startIcon={<CleaningServices />}
                onClick={() => setCleanupDialogOpen(true)}
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

        <TableContainer component={Paper} elevation={0} sx={{ borderRadius: 2, overflowX: 'auto', border: '1px solid', borderColor: 'divider' }}>
          <Table aria-label="Containers" size="small">
            <TableHead>
              <TableRow sx={{ bgcolor: theadBg }}>
                {visibleColumns.map((col) => (
                  <TableCell key={col.key} sx={{ color: theadColor, fontWeight: 600 }}>
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
              {filtered.map((c) => (
                <TableRow
                  key={c.containerId}
                  hover
                  onContextMenu={(e) => {
                    e.preventDefault()
                    setActionMenuAnchor(null)
                    setContextMenuPos({ top: e.clientY, left: e.clientX })
                    setActionMenuContainer(c)
                  }}
                >
                  {columnVisibility.names && (
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
                                onClick={() => {
                                  setScheduleContainerId(c.containerId)
                                  setScheduleContainerName(c.names)
                                  setScheduleExpiresAt(c.expiresAt)
                                  setScheduleOpen(true)
                                }}
                              />
                            </Tooltip>
                          )
                        })()}
                      </Box>
                    </TableCell>
                  )}
                  {columnVisibility.status && (
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
                  {columnVisibility.image && <TableCell sx={{ fontSize: '0.85rem' }}>{c.image.split(':')[0]}</TableCell>}
                  {columnVisibility.tag && <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{c.image.split(':')[1] ?? '-'}</TableCell>}
                  {columnVisibility.ports && (
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
                                rel="noreferrer"
                                clickable
                                sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', height: 24 }}
                              />
                            ))
                          : <Typography variant="body2" color="text.secondary">-</Typography>}
                      </Box>
                    </TableCell>
                  )}
                  {columnVisibility.ipAddress && (
                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      {c.ipAddress || <Typography variant="body2" color="text.secondary">-</Typography>}
                    </TableCell>
                  )}
                  {columnVisibility.database && (
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
                  {columnVisibility.expires && (
                    <TableCell>
                      {c.expiresAt ? (
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <ExpirationChip expiresAt={c.expiresAt} onCancel={() => handleCancelExpiration(c.containerId, c.names)} onExpired={loadContainers} />
                            <Tooltip title={t('containers.extendBy10')}>
                              <IconButton size="small" onClick={() => handleExtendExpiration(c.containerId)} sx={{ p: 0.25 }}>
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
                                onDelete={() => handleCancelDbDeletion(c.containerId, c.names)}
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
                  {columnVisibility.created && <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>{formatBackendDate(c.created)}</TableCell>}
                  {columnVisibility.containerId && <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>{c.containerId}</TableCell>}
                  {columnVisibility.command && <TableCell>{c.command}</TableCell>}
                  {columnVisibility.actions && (
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.25, alignItems: 'center' }}>
                        {isUp(c.status) ? (
                          <Tooltip title={stoppingId === c.containerId ? t('containers.stopping') : t('containers.stop')}>
                            <span>
                              <IconButton
                                size="small"
                                color="warning"
                                onClick={() => handleStop(c.containerId, c.names)}
                                disabled={stoppingId === c.containerId}
                              >
                                {stoppingId === c.containerId ? <CircularProgress size={18} color="inherit" /> : <Stop />}
                              </IconButton>
                            </span>
                          </Tooltip>
                        ) : (
                          <Tooltip title={t('containers.start')}>
                            <IconButton
                              size="small"
                              color="primary"
                              onClick={() => handleStart(c.containerId, c.names)}
                            >
                              <PlayArrow />
                            </IconButton>
                          </Tooltip>
                        )}
                        <IconButton
                          size="small"
                          onClick={(e) => {
                            setActionMenuAnchor(e.currentTarget)
                            setActionMenuContainer(c)
                          }}
                        >
                          <MoreVert />
                        </IconButton>
                      </Box>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>

        <Menu
          anchorEl={actionMenuAnchor}
          open={(Boolean(actionMenuAnchor) || Boolean(contextMenuPos)) && actionMenuContainer !== null}
          onClose={() => { setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null) }}
          {...(contextMenuPos && !actionMenuAnchor ? {
            anchorReference: 'anchorPosition' as const,
            anchorPosition: contextMenuPos,
          } : {
            transformOrigin: { horizontal: 'right', vertical: 'top' },
            anchorOrigin: { horizontal: 'right', vertical: 'bottom' },
          })}
          slotProps={{ paper: { sx: { minWidth: 200 } } }}
        >
          {actionMenuContainer && [
            isUp(actionMenuContainer.status) ? (
              <MenuItem
                key="stop"
                onClick={() => {
                  handleStop(actionMenuContainer.containerId, actionMenuContainer.names)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
                }}
                disabled={stoppingId === actionMenuContainer.containerId}
              >
                <ListItemIcon><Stop fontSize="small" color="warning" /></ListItemIcon>
                <ListItemText>{t('containers.stop')}</ListItemText>
              </MenuItem>
            ) : (
              <MenuItem
                key="start"
                onClick={() => {
                  handleStart(actionMenuContainer.containerId, actionMenuContainer.names)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
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
                handleViewLogs(actionMenuContainer)
                setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
              }}
            >
              <ListItemIcon><Terminal fontSize="small" /></ListItemIcon>
              <ListItemText>{t('containers.logs.viewLogs')}</ListItemText>
            </MenuItem>,

            isUp(actionMenuContainer.status) && (
              <MenuItem
                key="stats"
                onClick={() => {
                  setStatsContainerId(actionMenuContainer.containerId)
                  setStatsContainerName(actionMenuContainer.names)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
                }}
              >
                <ListItemIcon><Monitor fontSize="small" /></ListItemIcon>
                <ListItemText>{t('containers.stats.viewStats')}</ListItemText>
              </MenuItem>
            ),

            (dumpEnabled && actionMenuContainer.repository && actionMenuContainer.databaseName) || (migrationFeatureEnabled && actionMenuContainer.repository && actionMenuContainer.databaseName)
              ? <Divider key="db-divider" />
              : null,

            dumpEnabled && actionMenuContainer.repository && actionMenuContainer.databaseName && (
              <MenuItem
                key="snapshot"
                onClick={() => {
                  handleSnapshot(actionMenuContainer)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
                }}
              >
                <ListItemIcon><CameraAlt fontSize="small" /></ListItemIcon>
                <ListItemText>{t('containers.snapshotDatabase', { database: actionMenuContainer.databaseName })}</ListItemText>
              </MenuItem>
            ),

            migrationFeatureEnabled && actionMenuContainer.repository && actionMenuContainer.databaseName && (
              <MenuItem
                key="migration"
                onClick={() => {
                  setMigrationRepo(actionMenuContainer.repository!)
                  setMigrationDb(actionMenuContainer.databaseName!)
                  setMigrationOpen(true)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
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
                  setScheduleContainerId(actionMenuContainer.containerId)
                  setScheduleContainerName(actionMenuContainer.names)
                  setScheduleExpiresAt(actionMenuContainer.expiresAt)
                  setScheduleOpen(true)
                  setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
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
                handleRemove(actionMenuContainer.containerId, actionMenuContainer.names)
                setActionMenuAnchor(null); setContextMenuPos(null); setActionMenuContainer(null)
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

      <Dialog open={removeSse.events.length > 0} onClose={handleRemoveDialogClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('containers.removingContainer')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeSse.events} steps={REMOVE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeSse.hasError || removeSse.isDone) && (
            <Button onClick={handleRemoveDialogClose} color="inherit">{t('common.close')}</Button>
          )}
        </DialogActions>
      </Dialog>

      <CreateSnapshotModal
        open={snapshotOpen}
        onClose={() => {
          setSnapshotOpen(false)
          setSnapshotRepo(undefined)
          setSnapshotDb(undefined)
          setSnapshotContainerName(undefined)
        }}
        onCreated={() => {}}
        initialRepository={snapshotRepo}
        initialDatabase={snapshotDb}
        containerName={snapshotContainerName}
      />

      <ContainerLogsDialog
        open={logsContainerId !== null}
        containerId={logsContainerId ?? ''}
        containerName={logsContainerName}
        onClose={handleLogsClose}
      />

      <ContainerStatsDialog
        open={statsContainerId !== null}
        containerId={statsContainerId ?? ''}
        containerName={statsContainerName}
        onClose={() => {
          setStatsContainerId(null)
          setStatsContainerName('')
        }}
      />

      <RunMigrationModal
        open={migrationOpen}
        repository={migrationRepo}
        databaseName={migrationDb}
        onClose={() => {
          setMigrationOpen(false)
          setMigrationRepo('')
          setMigrationDb('')
        }}
        onCompleted={() => {
          loadContainers()
          getMigratedDatabases().then(setMigratedDatabases).catch(() => {})
        }}
      />

      <Dialog open={cleanupDialogOpen} onClose={() => !cleanupRunning && setCleanupDialogOpen(false)} maxWidth="sm" fullWidth>
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
              value={cleanupMinDays}
              onChange={(_, v) => setCleanupMinDays(v as number)}
              min={1}
              max={90}
              step={1}
              marks={DAY_MARKS}
              valueLabelDisplay="auto"
              valueLabelFormat={(v) => t('containers.cleanup.daysValue', { count: v })}
            />
          </Box>

          <Alert severity="info" sx={{ mb: 2 }}>
            {t('containers.cleanup.matchCount', { count: cleanupCandidates.length })}
          </Alert>

          {cleanupCandidates.length > 0 && (
            <Box sx={{ maxHeight: 150, overflowY: 'auto', mb: 1 }}>
              {cleanupCandidates.map(c => (
                <Typography key={c.containerId} variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.25 }}>
                  {c.names} — {getContainerAgeDays(c)}d
                </Typography>
              ))}
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setCleanupDialogOpen(false)} color="inherit" disabled={cleanupRunning}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={handleCleanup}
            disabled={cleanupRunning || cleanupCandidates.length === 0}
            startIcon={cleanupRunning ? <CircularProgress size={20} /> : <CleaningServices />}
          >
            {cleanupRunning ? t('containers.cleanup.running') : t('containers.cleanup.confirm', { count: cleanupCandidates.length })}
          </Button>
        </DialogActions>
      </Dialog>

      <QuickScheduleDialog
        open={scheduleOpen}
        containerId={scheduleContainerId}
        containerName={scheduleContainerName}
        expiresAt={scheduleExpiresAt}
        onClose={() => {
          setScheduleOpen(false)
          setScheduleContainerId('')
          setScheduleContainerName('')
          setScheduleExpiresAt(undefined)
          if (schedulingFeatureEnabled) loadContainerSchedules()
        }}
      />
    </>
  )
}

function ExpirationChip({ expiresAt, onCancel, onExpired }: { expiresAt: string; onCancel: () => void; onExpired: () => void }) {
  const { t } = useTranslation()
  const expiresMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState('')
  const expiredFired = useRef(false)
  const expiredTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    expiredFired.current = false
  }, [expiresMs])

  useEffect(() => {
    function update() {
      const diff = expiresMs - Date.now()
      if (diff <= 0) {
        setRemaining(t('containers.expiring'))
        if (!expiredFired.current) {
          expiredFired.current = true
          expiredTimerRef.current = setTimeout(onExpired, 6000)
        }
        return
      }
      const h = Math.floor(diff / 3600000)
      const m = Math.floor((diff % 3600000) / 60000)
      const s = Math.floor((diff % 60000) / 1000)
      setRemaining(h > 0 ? `${h}h ${m}m ${s}s` : m > 0 ? `${m}m ${s}s` : `${s}s`)
    }
    update()
    const id = setInterval(update, 1000)
    return () => {
      clearInterval(id)
      if (expiredTimerRef.current) clearTimeout(expiredTimerRef.current)
    }
  }, [expiresMs, onExpired, t])

  return (
    <Chip
      label={remaining}
      size="small"
      color="warning"
      icon={<Timer />}
      variant="outlined"
      onDelete={onCancel}
    />
  )
}
