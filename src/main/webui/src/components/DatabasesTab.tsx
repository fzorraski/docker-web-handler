import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { ManagedDatabaseInfo, ServerHealth, DatabaseHealthInfo, DatabaseActivity, DatabaseTableStats, DatabaseDump, DatabaseSnapshot, TopQuery } from '../types'
import {
  getManagedDatabaseRepositories,
  listManagedDatabases,
  deleteManagedDatabase,
  deleteManagedDatabasesBulk,
  toggleDatabaseProtected,
  getServerHealth,
  getDatabaseDetails,
  getDatabaseActivity,
  updateDatabaseDescription,
  enablePgStatStatements,
  resetQueryStats,
  resetTableStats,
  resetSingleTableStats,
  getTopQueriesForTable,
  getTopTempFileQueries,
  getDatabaseReportUrl,
} from '../services/managedDatabaseService'
import { useNotification } from './NotificationProvider'
import { useAuth } from './AuthProvider'
import PasswordConfirmDialog from './PasswordConfirmDialog'
import FullscreenToggleButton from './FullscreenToggleButton'
import CreateSnapshotModal from './CreateSnapshotModal'
import DumpBrowserModal from './DumpBrowserModal'
import RestoreDumpModal from './RestoreDumpModal'
import RunMigrationModal from './RunMigrationModal'
import QueryRunnerDialog from './QueryRunnerDialog'
import { listDumps } from '../services/dumpService'
import { isMigrationEnabled, getMigratedDatabases, type MigratedDatabase } from '../services/containerService'
import { isQueryEnabled } from '../services/managedDatabaseService'
import { validateOperationsPassword } from '../services/containerService'
import { RateLimitError } from '../services/fetchWithAuth'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useStickyHeader } from '../hooks/useStickyHeader'
import { useTablePagination } from '../hooks/useTablePagination'
import { useActionMenu } from '../hooks/useActionMenu'
import { useDatabaseCleanup } from '../hooks/useDatabaseCleanup'
import { formatBytes, formatDate } from '../utils/format'
import { copyToClipboard } from '../utils/clipboard'
import { getLastUsedColor, getLastUsedLabel } from '../utils/lastUsedColor'
import { useTranslation } from 'react-i18next'
import {
  Box,
  Typography,
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
  Button,
  CircularProgress,
  Chip,
  Checkbox,
  LinearProgress,
  Grid,
  Alert,
  Tabs,
  Tab,
  Tooltip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Slider,
  Switch,
  FormControlLabel,
  Menu,
  MenuItem,
  Stack,
  ListItemIcon,
  ListItemText,
  TablePagination,
  Divider,
  Collapse,
  IconButton,
} from '@mui/material'
import {
  Search,
  Delete,
  Shield,
  ShieldOutlined,
  CleaningServices,
  Warning,
  Storage,
  Dns,
  PeopleAlt,
  MonitorHeart,
  AccessTime,
  Speed,
  KeyboardArrowDown,
  KeyboardArrowUp,
  ContentCopy,
  Refresh,
  Edit,
  Check,
  Close,
  InfoOutlined,
  CameraAlt,
  Restore,
  SwapHoriz,
  Code,
  Download,
  RestartAlt,
} from '@mui/icons-material'

type PendingDelete =
  | { kind: 'single'; db: ManagedDatabaseInfo }
  | { kind: 'bulk'; names: string[] }

type PendingProtect = { db: ManagedDatabaseInfo }

const DB_COLUMNS: { key: string; label: string }[] = [
  { key: 'name', label: '' },
  { key: 'sizeBytes', label: '' },
  { key: 'activeConnections', label: '' },
  { key: 'effectiveLastUsedAt', label: '' },
  { key: 'protectedFlag', label: '' },
  { key: 'action', label: '' },
]

export default function DatabasesTab() {
  const { notify } = useNotification()
  const { rbacEnabled } = useAuth()
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx, theadCheckboxSx } = useTableHeaderTheme()
  const tableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(tableRef)

  const [repositories, setRepositories] = useState<string[]>([])
  const [activeRepo, setActiveRepo] = useState(0)
  const [databases, setDatabases] = useState<ManagedDatabaseInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [showWithConnections, setShowWithConnections] = useState(false)
  const [idleFilter, setIdleFilter] = useState<string>('all')

  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)
  const [pendingProtect, setPendingProtect] = useState<PendingProtect | null>(null)
  const [forceDeleteConfirm, setForceDeleteConfirm] = useState<{ db: ManagedDatabaseInfo; password: string; activeConnections: number } | null>(null)
  const [snapshotTarget, setSnapshotTarget] = useState<ManagedDatabaseInfo | null>(null)
  const [restoreTarget, setRestoreTarget] = useState<ManagedDatabaseInfo | null>(null)
  const [restorePasswordOpen, setRestorePasswordOpen] = useState(false)
  const [dumpBrowserOpen, setDumpBrowserOpen] = useState(false)
  const [restoreOpen, setRestoreOpen] = useState(false)
  const [restoreDump, setRestoreDump] = useState<DatabaseDump | null>(null)
  const [restoreSnapshot, setRestoreSnapshot] = useState<DatabaseSnapshot | null>(null)
  const [browseDumps, setBrowseDumps] = useState<DatabaseDump[]>([])
  const [migrationEnabled, setMigrationEnabled] = useState(false)
  const [migrationTarget, setMigrationTarget] = useState<ManagedDatabaseInfo | null>(null)
  const [queryFeatureEnabled, setQueryFeatureEnabled] = useState(false)
  const [queryWriteEnabled, setQueryWriteEnabled] = useState(false)
  const [queryStatsResetEnabled, setQueryStatsResetEnabled] = useState(false)
  const [queryTarget, setQueryTarget] = useState<ManagedDatabaseInfo | null>(null)
  const [migratedDatabases, setMigratedDatabases] = useState<MigratedDatabase[]>([])
  const [healthOpen, setHealthOpen] = useState(false)
  const [health, setHealth] = useState<ServerHealth | null>(null)
  const [healthLoading, setHealthLoading] = useState(false)
  const [dbHealthOpen, setDbHealthOpen] = useState(false)
  const [dbHealth, setDbHealth] = useState<DatabaseHealthInfo | null>(null)
  const [dbActivity, setDbActivity] = useState<DatabaseActivity | null>(null)
  const [dbTableStats, setDbTableStats] = useState<DatabaseTableStats | null>(null)
  const [dbHealthLoading, setDbHealthLoading] = useState(false)
  const [dbHealthTarget, setDbHealthTarget] = useState<ManagedDatabaseInfo | null>(null)
  const [dbHealthFullScreen, setDbHealthFullScreen] = useState(false)
  const [dbHealthTab, setDbHealthTab] = useState(0)
  const [pgssConfirmOpen, setPgssConfirmOpen] = useState(false)
  const [resetStatsConfirmOpen, setResetStatsConfirmOpen] = useState(false)
  const [resetTableStatsConfirmOpen, setResetTableStatsConfirmOpen] = useState(false)
  const [resetSingleTableTarget, setResetSingleTableTarget] = useState<{ schemaName: string; tableName: string } | null>(null)
  const dbHealthRequestId = useRef(0)
  const [editingDesc, setEditingDesc] = useState<string | null>(null) // database name being edited
  const [editDescValue, setEditDescValue] = useState('')
  const [descPasswordOpen, setDescPasswordOpen] = useState(false)
  const [dialogSort, setDialogSort] = useState<{ key: string; dir: 'asc' | 'desc' }>({ key: '', dir: 'asc' })
  const [expandedQuery, setExpandedQuery] = useState<number | null>(null)
  const [expandedSqlFull, setExpandedSqlFull] = useState<Set<number>>(new Set())
  const [tablesVisible, setTablesVisible] = useState(20)
  const [usedIdxVisible, setUsedIdxVisible] = useState(30)
  const [unusedIdxVisible, setUnusedIdxVisible] = useState(30)
  const [expandedImpact, setExpandedImpact] = useState<string | null>(null)
  const [impactQueries, setImpactQueries] = useState<Map<string, TopQuery[]>>(new Map())
  const [impactQueriesLoading, setImpactQueriesLoading] = useState<string | null>(null)
  const [expandedImpactQuery, setExpandedImpactQuery] = useState<number | null>(null)
  const impactAbortRef = useRef<AbortController | null>(null)
  const [serverHealthFullScreen, setServerHealthFullScreen] = useState(false)
  const [tempFileQueries, setTempFileQueries] = useState<TopQuery[]>([])
  const [tempFileQueriesLoading, setTempFileQueriesLoading] = useState(false)
  const [tempFileQueriesLoaded, setTempFileQueriesLoaded] = useState(false)
  const [expandedTempQuery, setExpandedTempQuery] = useState<number | null>(null)
  const [expandedTempSqlFull, setExpandedTempSqlFull] = useState<Set<number>>(new Set())
  const [tempQueriesVisible, setTempQueriesVisible] = useState(10)

  const menu = useActionMenu<ManagedDatabaseInfo>()

  const columns = useMemo(() => DB_COLUMNS.map(col => ({
    ...col,
    label: col.key === 'name' ? t('database.dbColumns.name')
      : col.key === 'sizeBytes' ? t('database.dbColumns.size')
      : col.key === 'activeConnections' ? t('database.dbColumns.connections')
      : col.key === 'effectiveLastUsedAt' ? t('database.dbColumns.idleSince')
      : col.key === 'protectedFlag' ? t('database.dbColumns.protected')
      : col.key === 'action' ? t('database.dbColumns.actions')
      : '',
  })), [t])

  const safeActiveRepo = activeRepo < repositories.length ? activeRepo : 0
  const currentRepo = repositories[safeActiveRepo] ?? ''

  const loadDatabases = useCallback(() => {
    if (!currentRepo) return
    setLoading(true)
    setLoadError('')
    setSelected(new Set())
    listManagedDatabases(currentRepo)
      .then(setDatabases)
      .catch((e) => {
        setDatabases([])
        setLoadError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => setLoading(false))
  }, [currentRepo])

  // Load repositories on mount
  useEffect(() => {
    getManagedDatabaseRepositories().then((repos) => {
      setRepositories(repos)
      setActiveRepo(0)
    })
  }, [])

  // Load databases and feature flags when repo changes
  useEffect(() => {
    if (!currentRepo) return
    loadDatabases()
    isMigrationEnabled().then(setMigrationEnabled).catch(() => setMigrationEnabled(false))
    getMigratedDatabases().then(setMigratedDatabases).catch(() => setMigratedDatabases([]))
    isQueryEnabled(currentRepo).then(r => { setQueryFeatureEnabled(r.enabled); setQueryWriteEnabled(r.writeEnabled); setQueryStatsResetEnabled(r.queryStatsResetEnabled) }).catch(() => {})
  }, [currentRepo, loadDatabases])

  // Auto-refresh every 60s
  useEffect(() => {
    if (!currentRepo) return
    const interval = setInterval(loadDatabases, 60000)
    return () => clearInterval(interval)
  }, [currentRepo, loadDatabases])

  const cleanup = useDatabaseCleanup({ notify, t, reload: loadDatabases })

  // Databases that would be affected by cleanup at current minDays
  const cleanupCandidates = useMemo(() => {
    if (cleanup.target === null) return []
    const cutoff = Date.now() - cleanup.minDays * 24 * 60 * 60 * 1000
    return databases.filter((db) => {
      if (db.protectedFlag) return false
      if (db.activeConnections > 0) return false // in use by containers
      // "Never used" databases are always eligible — they've been idle since forever
      if (!db.effectiveLastUsedAt) return true
      return new Date(db.effectiveLastUsedAt).getTime() < cutoff
    })
  }, [databases, cleanup.target, cleanup.minDays])

  // Filtering & sorting
  const filteredDatabases = useMemo(() => {
    let data = databases

    // Text filter
    if (filter) {
      const lc = filter.toLowerCase()
      data = data.filter((db) => db.name.toLowerCase().includes(lc))
    }

    // Connections filter
    if (showWithConnections) {
      data = data.filter((db) => db.activeConnections > 0)
    }

    // Idle since filter
    if (idleFilter === 'never') {
      data = data.filter((db) => !db.effectiveLastUsedAt)
    } else if (idleFilter !== 'all') {
      const days = parseInt(idleFilter, 10)
      if (!isNaN(days)) {
        const cutoff = Date.now() - days * 24 * 60 * 60 * 1000
        data = data.filter((db) => {
          if (!db.effectiveLastUsedAt) return true // never used = idle since forever
          return new Date(db.effectiveLastUsedAt).getTime() < cutoff
        })
      }
    }

    if (!sortKey) return data
    return [...data].sort((a, b) => {
      let cmp = 0
      if (sortKey === 'sizeBytes') {
        cmp = a.sizeBytes - b.sizeBytes
      } else if (sortKey === 'activeConnections') {
        cmp = a.activeConnections - b.activeConnections
      } else if (sortKey === 'protectedFlag') {
        cmp = (a.protectedFlag ? 1 : 0) - (b.protectedFlag ? 1 : 0)
      } else {
        const va = String((a as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
        const vb = String((b as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
        cmp = va.localeCompare(vb)
      }
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [databases, filter, sortKey, sortDir, showWithConnections, idleFilter])

  const pagination = useTablePagination(filteredDatabases, { storageKey: 'managedDatabases' })

  const maxSize = useMemo(() => Math.max(...databases.map(d => d.sizeBytes), 1), [databases])

  const metrics = useMemo(() => {
    let totalSize = 0
    let withConnections = 0
    let neverUsedCount = 0
    let neverUsedSize = 0
    let protectedCount = 0
    let inUseSize = 0

    for (const db of databases) {
      totalSize += db.sizeBytes
      if (db.protectedFlag) protectedCount++
      if (db.activeConnections > 0) {
        withConnections++
        inUseSize += db.sizeBytes
      }
      if (!db.effectiveLastUsedAt) {
        neverUsedCount++
        neverUsedSize += db.sizeBytes
      }
    }

    return { totalSize, withConnections, neverUsedCount, neverUsedSize, protectedCount, inUseSize }
  }, [databases])

  const healthMetrics = useMemo(() => {
    const totalConnections = databases.reduce((sum, d) => sum + d.activeConnections, 0)
    const sorted = [...databases].sort((a, b) => b.sizeBytes - a.sizeBytes)
    const largest = sorted[0] ?? null
    const top5 = sorted.slice(0, 5)
    const idle30 = databases.filter((db) => {
      if (!db.effectiveLastUsedAt) return true
      return Date.now() - new Date(db.effectiveLastUsedAt).getTime() > 30 * 24 * 60 * 60 * 1000
    })
    const idle30Size = idle30.reduce((sum, d) => sum + d.sizeBytes, 0)
    const avgSize = databases.length > 0 ? metrics.totalSize / databases.length : 0
    return { totalConnections, largest, top5, idle30Count: idle30.length, idle30Size, avgSize }
  }, [databases, metrics.totalSize])

  function openHealth() {
    setHealthOpen(true)
    setHealthLoading(true)
    getServerHealth(currentRepo)
      .then(setHealth)
      .catch(() => setHealth(null))
      .finally(() => setHealthLoading(false))
  }

  function openDbHealth(db: ManagedDatabaseInfo) {
    const requestId = ++dbHealthRequestId.current
    setDbHealthTarget(db)
    setDbHealthOpen(true)
    setDbHealthLoading(true)
    setDbHealthTab(0)
    setDbHealth(null)
    setDbActivity(null)
    setDbTableStats(null)
    setTempFileQueries([])
    setTempFileQueriesLoaded(false)
    setTempFileQueriesLoading(false)
    setExpandedTempQuery(null)
    setExpandedTempSqlFull(new Set())
    setTempQueriesVisible(10)
    getDatabaseDetails(currentRepo, db.name)
      .then((details) => {
        if (dbHealthRequestId.current !== requestId) return // stale response
        setDbHealth(details?.health ?? null)
        setDbActivity(details?.activity ?? null)
        setDbTableStats(details?.tableStats ?? null)
      })
      .catch(() => {
        if (dbHealthRequestId.current !== requestId) return
        setDbHealth(null)
        setDbActivity(null)
        setDbTableStats(null)
      })
      .finally(() => {
        if (dbHealthRequestId.current === requestId) setDbHealthLoading(false)
      })
  }

  function loadTempFileQueries() {
    if (!dbHealthTarget || tempFileQueriesLoading) return
    const requestId = dbHealthRequestId.current
    setTempFileQueriesLoading(true)
    getTopTempFileQueries(currentRepo, dbHealthTarget.name)
      .then((data) => {
        if (dbHealthRequestId.current !== requestId) return
        setTempFileQueries(data)
        setTempFileQueriesLoaded(true)
      })
      .finally(() => {
        if (dbHealthRequestId.current === requestId) setTempFileQueriesLoading(false)
      })
  }

  function handleDialogSort(key: string) {
    setDialogSort(prev => ({
      key,
      dir: prev.key === key && prev.dir === 'asc' ? 'desc' : 'asc',
    }))
  }

  function sortedList<T>(list: T[], getter: (item: T, key: string) => number | string): T[] {
    if (!dialogSort.key) return list
    return [...list].sort((a, b) => {
      const va = getter(a, dialogSort.key)
      const vb = getter(b, dialogSort.key)
      const cmp = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb))
      return dialogSort.dir === 'asc' ? cmp : -cmp
    })
  }

  function dialogSortLabel(key: string, label: string) {
    return (
      <TableSortLabel
        active={dialogSort.key === key}
        direction={dialogSort.key === key ? dialogSort.dir : 'asc'}
        onClick={() => handleDialogSort(key)}
      >
        {label}
      </TableSortLabel>
    )
  }

  function openRestorePassword(db: ManagedDatabaseInfo) {
    setRestoreTarget(db)
    setRestorePasswordOpen(true)
  }

  async function handleRestorePasswordConfirm(password: string) {
    const valid = await validateOperationsPassword(password)
    if (!valid) {
      notify(t('common.invalidOperationsPassword'), 'error')
      return
    }
    setRestorePasswordOpen(false)
    listDumps().then(setBrowseDumps).catch(() => setBrowseDumps([]))
    setDumpBrowserOpen(true)
  }

  function handleDumpSelected(dump: DatabaseDump) {
    setRestoreDump(dump)
    setRestoreSnapshot(null)
    setDumpBrowserOpen(false)
    setRestoreOpen(true)
  }

  function handleSnapshotSelected(snapshot: DatabaseSnapshot) {
    setRestoreSnapshot(snapshot)
    setRestoreDump(null)
    setDumpBrowserOpen(false)
    setRestoreOpen(true)
  }

  function startEditDesc(db: ManagedDatabaseInfo) {
    setEditingDesc(db.name)
    setEditDescValue(db.description ?? '')
  }

  function cancelEditDesc() {
    setEditingDesc(null)
    setEditDescValue('')
  }

  function saveEditDesc() {
    setDescPasswordOpen(true)
  }

  async function handleDescSave(password: string) {
    if (!editingDesc) return
    try {
      const result = await updateDatabaseDescription(currentRepo, editingDesc, editDescValue, password)
      if (result.success) {
        setDatabases(prev => prev.map(d => d.name === editingDesc ? { ...d, description: editDescValue || undefined } : d))
        setEditingDesc(null)
        setEditDescValue('')
        setDescPasswordOpen(false)
        listManagedDatabasesUseCase_invalidate()
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  // Helper to reload after description save (cache will be stale)
  function listManagedDatabasesUseCase_invalidate() {
    // The backend already invalidates the cache; next auto-refresh will pick it up
  }

  async function handleEnablePgss(password: string) {
    if (!dbHealthTarget) return
    try {
      const result = await enablePgStatStatements(currentRepo, dbHealthTarget.name, password)
      if (result.success) {
        if (result.alreadyInstalled) {
          notify(t('database.dbHealth.pgssAlreadyInstalled'), 'success')
        } else {
          notify(t('database.dbHealth.pgssEnabled'), 'success')
        }
        setPgssConfirmOpen(false)
        // Re-fetch activity to show top queries
        getDatabaseActivity(currentRepo, dbHealthTarget.name)
          .then(setDbActivity)
          .catch(() => {})
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  async function handleResetQueryStats(password: string) {
    if (!dbHealthTarget) return
    try {
      const result = await resetQueryStats(currentRepo, dbHealthTarget.name, password)
      if (result.success) {
        notify(t('database.dbHealth.queryStatsResetSuccess'), 'success')
        setResetStatsConfirmOpen(false)
        getDatabaseActivity(currentRepo, dbHealthTarget.name)
          .then(setDbActivity)
          .catch(() => {})
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  async function handleResetTableStats(password: string) {
    if (!dbHealthTarget) return
    try {
      const result = await resetTableStats(currentRepo, dbHealthTarget.name, password)
      if (result.success) {
        notify(t('database.dbHealth.tableStatsResetSuccess'), 'success')
        setResetTableStatsConfirmOpen(false)
        getDatabaseDetails(currentRepo, dbHealthTarget.name)
          .then(r => { if (r) { setDbTableStats(r.tableStats) } })
          .catch(() => {})
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  async function handleResetSingleTableStats(password: string) {
    if (!dbHealthTarget || !resetSingleTableTarget) return
    try {
      const result = await resetSingleTableStats(
        currentRepo, dbHealthTarget.name,
        resetSingleTableTarget.schemaName, resetSingleTableTarget.tableName, password,
      )
      if (result.success) {
        notify(t('database.dbHealth.singleTableStatsResetSuccess', { table: resetSingleTableTarget.tableName }), 'success')
        setResetSingleTableTarget(null)
        getDatabaseDetails(currentRepo, dbHealthTarget.name)
          .then(r => { if (r) { setDbTableStats(r.tableStats) } })
          .catch(() => {})
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  function handleSort(key: string) {
    if (key === 'action') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  function toggleSelect(name: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  function toggleSelectAll() {
    if (selected.size === filteredDatabases.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(filteredDatabases.map((d) => d.name)))
    }
  }

  function connectionColor(count: number): 'default' | 'info' | 'warning' | 'error' {
    if (count === 0) return 'default'
    if (count <= 5) return 'info'
    if (count <= 20) return 'warning'
    return 'error'
  }

  async function handleDeleteConfirm(password: string) {
    if (!pendingDelete) return
    try {
      if (pendingDelete.kind === 'single') {
        const result = await deleteManagedDatabase(currentRepo, pendingDelete.db.name, password)
        if (result.success) {
          notify(t('database.databaseDeleted'), 'success')
          setPendingDelete(null)
          loadDatabases()
        } else if (result.requiresForce) {
          // Active connections detected — ask user to confirm force delete
          setPendingDelete(null)
          setForceDeleteConfirm({ db: pendingDelete.db, password, activeConnections: result.activeConnections ?? 0 })
        } else {
          const msg = result.errorCode
            ? t(`database.deleteErrors.${result.errorCode}` as never, { count: result.count ?? 0, defaultValue: result.error })
            : result.error || t('database.deleteFailed')
          notify(msg, 'error')
        }
      } else {
        const result = await deleteManagedDatabasesBulk(currentRepo, pendingDelete.names, password)
        if (result.success) {
          notify(t('database.databasesDeleted', { count: result.deleted ?? 0 }), 'success')
          setPendingDelete(null)
          setSelected(new Set())
          loadDatabases()
        } else {
          notify(result.error || t('database.deleteFailed'), 'error')
        }
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  async function handleForceDelete() {
    if (!forceDeleteConfirm) return
    const { db, password } = forceDeleteConfirm
    setForceDeleteConfirm(null) // clear immediately before API call
    try {
      const result = await deleteManagedDatabase(currentRepo, db.name, password, true)
      if (result.success) {
        notify(t('database.databaseDeleted'), 'success')
        loadDatabases()
      } else {
        const msg = result.errorCode
          ? t(`database.deleteErrors.${result.errorCode}` as never, { count: result.count ?? 0, defaultValue: result.error })
          : result.error || t('database.deleteFailed')
        notify(msg, 'error')
      }
    } catch (e) {
      notify(t('common.unexpectedError'), 'error')
    }
  }

  async function handleProtectConfirm(password: string) {
    if (!pendingProtect) return
    try {
      const result = await toggleDatabaseProtected(currentRepo, pendingProtect.db.name, password)
      if (result.success) {
        const msg = result.protected
          ? t('database.protectedEnabled', { name: pendingProtect.db.name })
          : t('database.protectedDisabled', { name: pendingProtect.db.name })
        notify(msg, 'success')
        if (result.protected && result.disabledDeletionCount && result.disabledDeletionCount > 0) {
          notify(t('database.protectedAutoDisabledContainers', { count: result.disabledDeletionCount }), 'info')
        }
        setPendingProtect(null)
        loadDatabases()
      } else {
        notify(result.error || t('common.unexpectedError'), 'error')
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  function getDeleteTitle(): string {
    if (!pendingDelete) return ''
    return pendingDelete.kind === 'single'
      ? t('database.deleteDatabase')
      : t('database.deleteDatabases', { count: pendingDelete.names.length })
  }

  function getDeleteMessage(): string {
    if (!pendingDelete) return ''
    return pendingDelete.kind === 'single'
      ? t('database.deleteDatabaseConfirm', { name: pendingDelete.db.name, repository: currentRepo })
      : t('database.deleteDatabasesBulkConfirm', { count: pendingDelete.names.length })
  }

  if (repositories.length === 0 && !loading) {
    return (
      <Alert severity="info" sx={{ mt: 2 }}>
        {t('database.noDatabaseConfig')}
      </Alert>
    )
  }

  return (
    <>
      {/* Repository sub-tabs */}
      {repositories.length > 1 && (
        <Tabs
          value={safeActiveRepo}
          onChange={(_e, v) => setActiveRepo(v)}
          sx={{ mb: 2 }}
          variant="scrollable"
          scrollButtons="auto"
        >
          {repositories.map((repo) => (
            <Tab key={repo} label={repo} />
          ))}
        </Tabs>
      )}

      {/* Summary card */}
      {databases.length > 0 && (
        <Paper elevation={2} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
          <Grid container spacing={3} alignItems="center">
            <Grid size={{ xs: 6, sm: 4, md: 'grow' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Dns color="primary" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                    {databases.length}
                  </Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.6rem', fontWeight: 600 }}>
                    {t('database.dbSummary.databases')}
                  </Typography>
                  <Typography sx={{ color: 'info.main', fontSize: '0.65rem', fontWeight: 600 }}>
                    {metrics.withConnections} {t('database.dbSummary.inUse')}
                  </Typography>
                </Box>
              </Box>
            </Grid>
            <Grid size={{ xs: 6, sm: 4, md: 'grow' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Storage color="action" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                    {formatBytes(metrics.totalSize)}
                  </Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.6rem', fontWeight: 600 }}>
                    {t('database.dbSummary.totalSize')}
                  </Typography>
                </Box>
              </Box>
            </Grid>
            <Grid size={{ xs: 6, sm: 4, md: 'grow' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Warning color="warning" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                    {metrics.neverUsedCount}
                  </Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.6rem', fontWeight: 600 }}>
                    {t('database.dbSummary.neverUsed')}
                  </Typography>
                  {metrics.neverUsedSize > 0 && (
                    <Typography sx={{ color: 'error.main', fontSize: '0.65rem', fontWeight: 600 }}>
                      {formatBytes(metrics.neverUsedSize)} {t('database.dbSummary.wasted')}
                    </Typography>
                  )}
                </Box>
              </Box>
            </Grid>
            <Grid size={{ xs: 6, sm: 4, md: 'grow' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Shield color="success" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                    {metrics.protectedCount}
                  </Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.6rem', fontWeight: 600 }}>
                    {t('database.dbSummary.protected')}
                  </Typography>
                </Box>
              </Box>
            </Grid>
          </Grid>
        </Paper>
      )}

      {loadError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="error" size="small" onClick={loadDatabases} startIcon={<Refresh />}>
              {t('database.dbHealth.refresh')}
            </Button>
          }
        >
          {loadError}
        </Alert>
      )}

      {/* Controls */}
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2, flexWrap: 'wrap', gap: 1 }}>
        <TextField
          size="small"
          placeholder={t('database.searchDatabases')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search />
                </InputAdornment>
              ),
            },
          }}
          sx={{ minWidth: 250 }}
        />
        <FormControlLabel
          control={<Switch checked={showWithConnections} onChange={(e) => setShowWithConnections(e.target.checked)} size="small" />}
          label={<Typography variant="body2">{t('database.filterWithConnections')}</Typography>}
        />
        <TextField
          select
          size="small"
          value={idleFilter}
          onChange={(e) => setIdleFilter(e.target.value)}
          label={t('database.filterIdleSince')}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="all">{t('database.idleAll')}</MenuItem>
          <MenuItem value="7">{t('database.idleOver7d')}</MenuItem>
          <MenuItem value="14">{t('database.idleOver14d')}</MenuItem>
          <MenuItem value="30">{t('database.idleOver30d')}</MenuItem>
          <MenuItem value="60">{t('database.idleOver60d')}</MenuItem>
          <MenuItem value="never">{t('database.idleNeverUsed')}</MenuItem>
        </TextField>
        <Box sx={{ flex: 1 }} />
        {selected.size > 0 && (
          <Button
            variant="contained"
            color="error"
            size="small"
            startIcon={<Delete />}
            onClick={() => setPendingDelete({ kind: 'bulk', names: [...selected] })}
          >
            {t('common.delete')} ({selected.size})
          </Button>
        )}
        <Button
          variant="outlined"
          size="small"
          startIcon={<MonitorHeart />}
          onClick={openHealth}
          color="info"
        >
          {t('database.dbHealth.button')}
        </Button>
        <Button
          variant="contained"
          color="warning"
          size="small"
          startIcon={<CleaningServices />}
          onClick={() => cleanup.open(currentRepo)}
        >
          {t('database.cleanUpByIdle')}
        </Button>
      </Stack>

      {/* Table */}
      <Paper elevation={2} sx={{ borderRadius: 2 }}>
        <TableContainer ref={tableRef}>
          <Table stickyHeader aria-label="Managed databases">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                  <Checkbox
                    checked={filteredDatabases.length > 0 && selected.size === filteredDatabases.length}
                    indeterminate={selected.size > 0 && selected.size < filteredDatabases.length}
                    onChange={toggleSelectAll}
                    sx={theadCheckboxSx}
                  />
                </TableCell>
                {columns.map((col) => (
                  <TableCell key={col.key} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>
                    {col.key !== 'action' ? (
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
                  <TableCell colSpan={columns.length + 1} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filteredDatabases.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns.length + 1} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {t('database.noDatabasesFound')}
                  </TableCell>
                </TableRow>
              )}
              {pagination.paginatedData.map((db) => (
                <TableRow
                  key={db.name}
                  hover
                  sx={{ cursor: 'pointer' }}
                  selected={selected.has(db.name)}
                  onContextMenu={(e) => {
                    e.preventDefault()
                    menu.openByPosition({ top: e.clientY, left: e.clientX }, db)
                  }}
                >
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={selected.has(db.name)}
                      onChange={() => toggleSelect(db.name)}
                    />
                  </TableCell>
                  <TableCell>
                    {editingDesc === db.name ? (
                      <Box>
                        <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 600, fontSize: '0.875rem', mb: 0.5 }}>
                          {db.name}
                        </Typography>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                          <TextField
                            size="small"
                            value={editDescValue}
                            onChange={(e) => setEditDescValue(e.target.value)}
                            placeholder={t('common.description')}
                            variant="standard"
                            autoFocus
                            sx={{ flex: 1, fontSize: '0.8rem' }}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') saveEditDesc()
                              if (e.key === 'Escape') cancelEditDesc()
                            }}
                          />
                          <IconButton size="small" onClick={saveEditDesc} color="primary"><Check sx={{ fontSize: 16 }} /></IconButton>
                          <IconButton size="small" onClick={cancelEditDesc}><Close sx={{ fontSize: 16 }} /></IconButton>
                        </Box>
                      </Box>
                    ) : (
                      <Tooltip
                        title={db.description ? (
                          <Box sx={{ p: 0.5 }}>
                            <Typography variant="caption" fontWeight={700} sx={{ display: 'block', mb: 0.5, opacity: 0.8 }}>
                              {t('common.description')}
                            </Typography>
                            <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                              {db.description}
                            </Typography>
                          </Box>
                        ) : ''}
                        placement="bottom-start"
                        arrow
                        disableHoverListener={!db.description}
                        slotProps={{
                          tooltip: {
                            sx: {
                              bgcolor: 'primary.dark', maxWidth: 360, borderRadius: 2,
                              px: 2, py: 1.5, boxShadow: 3,
                              '& .MuiTooltip-arrow': { color: 'primary.dark', left: '50% !important', transform: 'translateX(-50%) !important' },
                            },
                          },
                        }}
                      >
                        <Box
                          onClick={() => startEditDesc(db)}
                          sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.5, '&:hover .edit-icon': { opacity: 1 } }}
                        >
                          <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 600, fontSize: '0.875rem' }}>
                            {db.name}
                          </Typography>
                          {db.description && <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary' }} />}
                          <Edit className="edit-icon" sx={{ fontSize: 14, opacity: 0, color: 'text.secondary', transition: 'opacity 0.2s' }} />
                          {db.containerCount > 0 && (
                            <Tooltip title={t('database.containerCountTooltip', { count: db.containerCount })}>
                              <Chip
                                label={db.containerCount}
                                size="small"
                                color="info"
                                variant="outlined"
                                icon={<Dns sx={{ fontSize: 12 }} />}
                                sx={{ fontSize: '0.65rem', height: 18, ml: 0.5 }}
                              />
                            </Tooltip>
                          )}
                          {db.earliestExpiration && (
                            <Tooltip title={db.scheduledForDeletion
                              ? t('database.expirationDeleteTooltip', { date: formatDate(db.earliestExpiration) })
                              : t('database.expirationTooltip', { date: formatDate(db.earliestExpiration) })
                            }>
                              <Chip
                                label={formatDate(db.earliestExpiration)}
                                size="small"
                                color={db.scheduledForDeletion ? 'error' : 'warning'}
                                variant="outlined"
                                icon={<AccessTime sx={{ fontSize: 12 }} />}
                                sx={{ fontSize: '0.65rem', height: 18, ml: 0.5 }}
                              />
                            </Tooltip>
                          )}
                          {(() => {
                            const migration = migratedDatabases.find(m => m.databaseName === db.name)
                            if (!migration) return null
                            let label = migration.mode === 'API' && migration.sourceVersion && migration.targetVersion
                              ? `${t('database.dbMigrated')} (${migration.sourceVersion} \u2192 ${migration.targetVersion})`
                              : t('database.dbMigrated')
                            if (migration.migratedAt) label += ` — ${formatDate(migration.migratedAt)}`
                            return (
                              <Tooltip title={label}>
                                <SwapHoriz sx={{ fontSize: 16, color: 'info.main', ml: 0.5 }} />
                              </Tooltip>
                            )
                          })()}
                          {db.lastRestoredFrom && (
                            <Tooltip title={`${t('database.dbRestored')}: ${db.lastRestoredFrom}${db.lastRestoredAt ? ' (' + formatDate(db.lastRestoredAt) + ')' : ''}`}>
                              <Restore sx={{ fontSize: 16, color: 'success.main', ml: 0.5 }} />
                            </Tooltip>
                          )}
                        </Box>
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 150 }}>
                      <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", minWidth: 80, whiteSpace: 'nowrap' }}>
                        {formatBytes(db.sizeBytes)}
                      </Typography>
                      <LinearProgress
                        variant="determinate"
                        value={Math.min(100, (db.sizeBytes / maxSize) * 100)}
                        sx={{
                          flex: 1,
                          height: 6,
                          borderRadius: 3,
                          bgcolor: 'grey.200',
                          '& .MuiLinearProgress-bar': {
                            borderRadius: 3,
                            bgcolor: db.sizeBytes / maxSize > 0.9 ? 'error.main'
                              : db.sizeBytes / maxSize > 0.7 ? 'warning.main'
                              : 'primary.main',
                          },
                        }}
                      />
                    </Box>
                  </TableCell>
                  <TableCell>
                    <Chip
                      icon={<PeopleAlt sx={{ fontSize: 16 }} />}
                      label={db.activeConnections}
                      size="small"
                      color={connectionColor(db.activeConnections)}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={getLastUsedLabel(
                        db.effectiveLastUsedAt ?? null,
                        false,
                        '',
                        t('database.neverUsed'),
                        { condition: (db.containerCount ?? 0) > 0, label: t('database.inUseByContainer') },
                      )}
                      color={getLastUsedColor(
                        db.effectiveLastUsedAt ?? null,
                        false,
                        (db.containerCount ?? 0) > 0,
                      )}
                      size="small"
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={t('database.toggleProtected')}>
                      <Chip
                        icon={db.protectedFlag ? <Shield sx={{ fontSize: 16 }} /> : <ShieldOutlined sx={{ fontSize: 16 }} />}
                        label={db.protectedFlag ? 'Yes' : 'No'}
                        size="small"
                        color={db.protectedFlag ? 'success' : 'default'}
                        variant={db.protectedFlag ? 'filled' : 'outlined'}
                        onClick={() => setPendingProtect({ db })}
                        sx={{ cursor: 'pointer' }}
                      />
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Tooltip title={db.protectedFlag ? t('database.databaseProtected') : t('common.delete')}>
                      <span>
                        <Button
                          size="small"
                          color="error"
                          disabled={db.protectedFlag}
                          onClick={() => setPendingDelete({ kind: 'single', db })}
                          sx={{ minWidth: 'auto', p: 0.5 }}
                        >
                          <Delete fontSize="small" />
                        </Button>
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
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
      </Paper>

      {/* Context menu */}
      <Menu
        open={Boolean(menu.contextMenuPos) && menu.target !== null}
        onClose={() => menu.close()}
        anchorReference="anchorPosition"
        anchorPosition={menu.contextMenuPos ?? undefined}
        slotProps={{ ...menu.menuSlotProps, paper: { sx: { minWidth: 200 } } }}
      >
        {menu.target && [
          <MenuItem
            key="health"
            onClick={() => { openDbHealth(menu.target!); menu.close() }}
          >
            <ListItemIcon><MonitorHeart fontSize="small" color="info" /></ListItemIcon>
            <ListItemText>{t('database.dbHealth.button')}</ListItemText>
          </MenuItem>,
          <Divider key="divider0" />,
          <MenuItem
            key="snapshot"
            onClick={() => { setSnapshotTarget(menu.target!); menu.close() }}
          >
            <ListItemIcon><CameraAlt fontSize="small" color="primary" /></ListItemIcon>
            <ListItemText>{t('database.createSnapshot')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="restore"
            onClick={() => { openRestorePassword(menu.target!); menu.close() }}
          >
            <ListItemIcon><Restore fontSize="small" color="success" /></ListItemIcon>
            <ListItemText>{t('common.restore')}</ListItemText>
          </MenuItem>,
          ...(migrationEnabled ? [
            <MenuItem
              key="migration"
              onClick={() => { setMigrationTarget(menu.target!); menu.close() }}
            >
              <ListItemIcon><SwapHoriz fontSize="small" /></ListItemIcon>
              <ListItemText>{t('database.runMigration')}</ListItemText>
            </MenuItem>,
          ] : []),
          ...(queryFeatureEnabled ? [
            <MenuItem
              key="query"
              onClick={() => { setQueryTarget(menu.target!); menu.close() }}
            >
              <ListItemIcon><Code fontSize="small" color="secondary" /></ListItemIcon>
              <ListItemText>{t('database.query.runQuery')}</ListItemText>
            </MenuItem>,
          ] : []),
          <Divider key="divider1" />,
          <MenuItem
            key="protect"
            onClick={() => { setPendingProtect({ db: menu.target! }); menu.close() }}
          >
            <ListItemIcon>
              {menu.target.protectedFlag
                ? <ShieldOutlined fontSize="small" color="warning" />
                : <Shield fontSize="small" color="success" />}
            </ListItemIcon>
            <ListItemText>{menu.target.protectedFlag ? t('database.removeProtection') : t('database.enableProtection')}</ListItemText>
          </MenuItem>,
          <Divider key="divider2" />,
          <MenuItem
            key="delete"
            disabled={menu.target.protectedFlag}
            onClick={() => { setPendingDelete({ kind: 'single', db: menu.target! }); menu.close() }}
            sx={{ color: menu.target.protectedFlag ? undefined : 'error.main' }}
          >
            <ListItemIcon><Delete fontSize="small" color={menu.target.protectedFlag ? 'disabled' : 'error'} /></ListItemIcon>
            <ListItemText>{t('common.delete')}</ListItemText>
          </MenuItem>,
        ]}
      </Menu>

      {/* Delete confirmation dialog */}
      <PasswordConfirmDialog
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        onConfirm={handleDeleteConfirm}
        title={getDeleteTitle()}
        message={getDeleteMessage()}
      />

      {/* Protected toggle confirmation dialog */}
      <PasswordConfirmDialog
        open={pendingProtect !== null}
        onClose={() => setPendingProtect(null)}
        onConfirm={handleProtectConfirm}
        title={t('database.toggleProtected')}
        message={
          pendingProtect?.db.protectedFlag
            ? t('database.protectedDisableConfirm', { name: pendingProtect?.db.name ?? '' })
            : t('database.protectedEnableConfirm', { name: pendingProtect?.db.name ?? '' })
        }
        confirmColor={pendingProtect?.db.protectedFlag ? 'warning' : 'success'}
        icon={pendingProtect?.db.protectedFlag ? <ShieldOutlined /> : <Shield />}
        confirmLabel={pendingProtect?.db.protectedFlag ? t('database.removeProtection') : t('database.enableProtection')}
      />

      {/* Cleanup by idle dialog */}
      <Dialog open={cleanup.target !== null} onClose={cleanup.close} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <CleaningServices sx={{ mr: 1, verticalAlign: 'middle' }} />
          {t('database.cleanUpDatabases')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Typography sx={{ mb: 2 }}>
            {t('database.cleanUpDatabasesDesc')}
          </Typography>
          <Alert severity="info" icon={<Warning />} sx={{ mb: 3 }}>
            {t('database.dbIdleTrackingWarning')}
          </Alert>
          <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
            {t('database.minDaysLabel')}
          </Typography>
          <Box sx={{ px: 2, mb: 3 }}>
            <Slider
              value={cleanup.minDays}
              onChange={(_, v) => cleanup.setMinDays(v as number)}
              min={1}
              max={90}
              step={1}
              marks={[
                { value: 1, label: '1' },
                { value: 7, label: '7' },
                { value: 14, label: '14' },
                { value: 30, label: '30' },
                { value: 60, label: '60' },
                { value: 90, label: '90' },
              ]}
              valueLabelDisplay="auto"
              valueLabelFormat={(v) => t('database.daysValue', { count: v })}
            />
          </Box>
          {cleanupCandidates.length > 0 ? (
            <Alert severity="warning" sx={{ mb: 3 }}>
              <Typography variant="body2" fontWeight={600} sx={{ mb: 0.5 }}>
                {t('database.dbCleanupAffected', { count: cleanupCandidates.length })}
              </Typography>
              <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                {cleanupCandidates.map((db) => (
                  <li key={db.name}>
                    <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      {db.name}
                      <Typography component="span" variant="caption" sx={{ ml: 1, color: 'text.secondary' }}>
                        ({formatBytes(db.sizeBytes)})
                      </Typography>
                    </Typography>
                  </li>
                ))}
              </Box>
            </Alert>
          ) : (
            <Alert severity="success" sx={{ mb: 3 }}>
              {t('database.dbCleanupNoneAffected')}
            </Alert>
          )}
          {!rbacEnabled && (
            <TextField
              fullWidth
              type="password"
              label={t('common.operationsPassword')}
              value={cleanup.password}
              onChange={(e) => cleanup.setPassword(e.target.value)}
              size="small"
              autoComplete="off"
              error={!!cleanup.error}
              helperText={cleanup.error}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && cleanup.password) cleanup.confirm()
              }}
            />
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={cleanup.close} disabled={cleanup.loading}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={cleanup.confirm}
            disabled={(!rbacEnabled && !cleanup.password) || cleanup.loading}
          >
            {cleanup.loading ? <CircularProgress size={20} /> : t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Per-database Health dialog */}
      <Dialog open={dbHealthOpen} onClose={() => { setDbHealthOpen(false); setDbHealthFullScreen(false) }} maxWidth="md" fullWidth fullScreen={dbHealthFullScreen}>
        <DialogTitle sx={{ bgcolor: 'info.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <MonitorHeart sx={{ mr: 1 }} />
          <Box sx={{ flex: 1 }}>{dbHealthTarget?.name}</Box>
          <Tooltip title={t('database.dbHealth.refresh')}>
            <IconButton
              size="small"
              sx={{ color: 'white' }}
              disabled={dbHealthLoading}
              onClick={() => { if (dbHealthTarget) openDbHealth(dbHealthTarget) }}
            >
              <Refresh sx={{ fontSize: 18, animation: dbHealthLoading ? 'spin 1s linear infinite' : 'none', '@keyframes spin': { '100%': { transform: 'rotate(360deg)' } } }} />
            </IconButton>
          </Tooltip>
          <Tooltip title={t('database.dbHealth.downloadReport')}>
            <IconButton
              size="small"
              sx={{ color: 'white' }}
              onClick={() => { if (dbHealthTarget) window.open(getDatabaseReportUrl(currentRepo, dbHealthTarget.name)) }}
            >
              <Download sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <FullscreenToggleButton fullScreen={dbHealthFullScreen} onToggle={() => setDbHealthFullScreen(f => !f)} color="white" />
        </DialogTitle>
        <Tabs value={dbHealthTab} onChange={(_e, v) => { setDbHealthTab(v); setDialogSort({ key: '', dir: 'asc' }) }} sx={{ px: 2, borderBottom: 1, borderColor: 'divider' }}>
          <Tab label={t('database.dbHealth.tabHealth')} />
          <Tab label={t('database.dbHealth.tabActivity')} />
          <Tab label={t('database.dbHealth.tabQueries')} />
          <Tab label={t('database.dbHealth.tabTables')} />
          <Tab label={t('database.dbHealth.tabIndexes')} />
        </Tabs>
        <DialogContent sx={{ pt: 3 }}>
          {dbHealthLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
              <CircularProgress size={32} />
            </Box>
          ) : (
            <>
              {/* ===== Health tab ===== */}
              {dbHealthTab === 0 && (dbHealth ? (
                <Stack spacing={2.5}>
                  {/* Performance */}
                  <Paper variant="outlined" sx={{ p: 2 }}>
                    <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                      {t('database.dbHealth.performance')}
                    </Typography>
                    <Grid container spacing={2}>
                      <Grid size={{ xs: 6 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.cacheHitRatio')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{
                          fontFamily: "'JetBrains Mono', monospace",
                          color: dbHealth.cacheHitRatio >= 90 ? 'success.main' : dbHealth.cacheHitRatio >= 70 ? 'warning.main' : 'error.main',
                        }}>
                          {dbHealth.cacheHitRatio.toFixed(2)}%
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 6 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbColumns.size')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                          {formatBytes(dbHealth.sizeBytes)}
                        </Typography>
                      </Grid>
                    </Grid>
                    <Box sx={{ mt: 1.5 }}>
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.cacheHitRatio')}</Typography>
                        <Typography variant="caption" fontWeight="bold">{dbHealth.cacheHitRatio.toFixed(1)}%</Typography>
                      </Box>
                      <LinearProgress
                        variant="determinate"
                        value={Math.min(100, dbHealth.cacheHitRatio)}
                        sx={{
                          height: 8, borderRadius: 4, bgcolor: 'grey.200',
                          '& .MuiLinearProgress-bar': {
                            borderRadius: 4,
                            bgcolor: dbHealth.cacheHitRatio >= 90 ? 'success.main' : dbHealth.cacheHitRatio >= 70 ? 'warning.main' : 'error.main',
                          },
                        }}
                      />
                    </Box>
                  </Paper>

                  {/* Connections */}
                  <Paper variant="outlined" sx={{ p: 2 }}>
                    <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                      {t('database.dbHealth.connections')}
                    </Typography>
                    <Grid container spacing={2}>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.active')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'info.main' }}>
                          {dbHealth.activeConnections}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.waiting')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: dbHealth.waitingConnections > 0 ? 'warning.main' : 'text.primary' }}>
                          {dbHealth.waitingConnections}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.longRunning')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: dbHealth.longRunningQueries > 0 ? 'error.main' : 'text.primary' }}>
                          {dbHealth.longRunningQueries}
                        </Typography>
                      </Grid>
                    </Grid>
                  </Paper>

                  {/* Transactions */}
                  <Paper variant="outlined" sx={{ p: 2 }}>
                    <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                      {t('database.dbHealth.transactions')}
                    </Typography>
                    <Grid container spacing={2}>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.commits')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'success.main' }}>
                          {dbHealth.xactCommit.toLocaleString()}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.rollbacks')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: dbHealth.xactRollback > 0 ? 'warning.main' : 'text.primary' }}>
                          {dbHealth.xactRollback.toLocaleString()}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.rollbackRatio')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{
                          fontFamily: "'JetBrains Mono', monospace",
                          color: dbHealth.xactCommit + dbHealth.xactRollback > 0 && dbHealth.xactRollback / (dbHealth.xactCommit + dbHealth.xactRollback) > 0.05 ? 'error.main' : 'text.primary',
                        }}>
                          {dbHealth.xactCommit + dbHealth.xactRollback > 0
                            ? (dbHealth.xactRollback / (dbHealth.xactCommit + dbHealth.xactRollback) * 100).toFixed(2)
                            : '0.00'}%
                        </Typography>
                      </Grid>
                    </Grid>
                  </Paper>

                  {/* Maintenance */}
                  <Paper variant="outlined" sx={{ p: 2 }}>
                    <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                      {t('database.dbHealth.maintenance')}
                    </Typography>
                    <Grid container spacing={2}>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.deadTuples')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: dbHealth.deadTuples > 10000 ? 'error.main' : dbHealth.deadTuples > 1000 ? 'warning.main' : 'text.primary' }}>
                          {dbHealth.deadTuples.toLocaleString()}
                        </Typography>
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.tempFiles')}</Typography>
                        <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                          {dbHealth.tempFiles.toLocaleString()}
                          {dbHealth.tempBytes > 0 && (
                            <Typography component="span" variant="caption" sx={{ ml: 0.5, color: 'text.secondary' }}>
                              ({formatBytes(dbHealth.tempBytes)})
                            </Typography>
                          )}
                        </Typography>
                        {dbHealth.tempFiles > 0 && dbActivity?.pgStatStatementsAvailable && (
                          <Button
                            size="small"
                            sx={{ mt: 0.5, textTransform: 'none', fontSize: '0.65rem', p: 0, minWidth: 'auto' }}
                            onClick={() => { setDbHealthTab(2); if (!tempFileQueriesLoaded) loadTempFileQueries() }}
                          >
                            {t('database.dbHealth.viewTempQueries')}
                          </Button>
                        )}
                      </Grid>
                      <Grid size={{ xs: 4 }}>
                        <Typography variant="caption" color="text.secondary">{t('database.dbHealth.txIdAge')}</Typography>
                        <Tooltip title={dbHealth.txIdAge > 1_000_000_000 ? t('database.dbHealth.txIdAgeWarning') : ''}>
                          <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: dbHealth.txIdAge > 1_000_000_000 ? 'error.main' : dbHealth.txIdAge > 500_000_000 ? 'warning.main' : 'text.primary' }}>
                            {(dbHealth.txIdAge / 1_000_000).toFixed(1)}M
                          </Typography>
                        </Tooltip>
                      </Grid>
                    </Grid>
                  </Paper>
                </Stack>
              ) : (
                <Alert severity="error">{t('common.unexpectedError')}</Alert>
              ))}

              {/* ===== Activity tab ===== */}
              {dbHealthTab === 1 && (
                <Stack spacing={2.5}>
                  {/* Top Users */}
                  {dbActivity && dbActivity.topUsers.length > 0 && (
                    <Paper variant="outlined" sx={{ p: 2 }}>
                      <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                        {t('database.dbHealth.topUsers')}
                      </Typography>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem', py: 0.5 }}>{dialogSortLabel('user', t('database.dbHealth.user'))}</TableCell>
                            <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.75rem', py: 0.5 }}>{dialogSortLabel('connections', t('database.dbHealth.totalConn'))}</TableCell>
                            <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.75rem', py: 0.5 }}>{dialogSortLabel('active', t('database.dbHealth.active'))}</TableCell>
                            <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.75rem', py: 0.5 }}>{dialogSortLabel('idle', t('database.dbHealth.idleConn'))}</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {sortedList(dbActivity.topUsers, (u, k) => k === 'user' ? u.user : k === 'connections' ? u.connections : k === 'active' ? u.active : u.idle).map((u) => (
                            <TableRow key={u.user}>
                              <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.5 }}>{u.user}</TableCell>
                              <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.5 }}>{u.connections}</TableCell>
                              <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.5, color: 'info.main' }}>{u.active}</TableCell>
                              <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem', py: 0.5 }}>{u.idle}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </Paper>
                  )}

                  {/* Active Sessions */}
                  {dbActivity && dbActivity.sessions.length > 0 && (
                    <Paper variant="outlined" sx={{ p: 2 }}>
                      <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                        {t('database.dbHealth.activeSessions')} ({dbActivity.sessions.length})
                      </Typography>
                      <Box sx={{ maxHeight: dbHealthFullScreen ? undefined : 350, overflow: 'auto' }}>
                        {dbActivity.sessions.map((s, i) => (
                          <Box key={i} sx={{ mb: 1.5, pb: 1.5, borderBottom: i < dbActivity.sessions.length - 1 ? '1px solid' : 'none', borderColor: 'divider' }}>
                            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                              <Chip label={s.state ?? 'unknown'} size="small" variant="outlined"
                                color={s.state === 'active' ? 'info' : s.state === 'idle' ? 'default' : 'warning'} />
                              <Typography variant="caption" color="text.secondary">{s.user}</Typography>
                              {s.clientAddr && <Typography variant="caption" color="text.secondary">{s.clientAddr}</Typography>}
                              {s.durationSeconds > 0 && (
                                <Typography variant="caption" sx={{ color: s.durationSeconds > 60 ? 'error.main' : s.durationSeconds > 10 ? 'warning.main' : 'text.secondary' }}>
                                  {s.durationSeconds > 3600 ? `${Math.floor(s.durationSeconds / 3600)}h ${Math.floor((s.durationSeconds % 3600) / 60)}m`
                                    : s.durationSeconds > 60 ? `${Math.floor(s.durationSeconds / 60)}m ${s.durationSeconds % 60}s`
                                    : `${s.durationSeconds}s`}
                                </Typography>
                              )}
                              {s.waitEventType && <Chip label={s.waitEventType} size="small" color="warning" variant="outlined" sx={{ fontSize: '0.65rem', height: 18 }} />}
                            </Stack>
                            {s.query && (
                              <Stack direction="row" alignItems="flex-start" spacing={0.5}>
                                <Typography variant="body2" sx={{
                                  fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem',
                                  bgcolor: 'action.hover', borderRadius: 1, p: 0.75,
                                  whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 60, overflow: 'auto',
                                  flex: 1,
                                }}>
                                  {s.query}
                                </Typography>
                                <Tooltip title={t('database.dbHealth.qCopy')}>
                                  <IconButton
                                    size="small"
                                    onClick={() => copyToClipboard(s.query).then(() => notify(t('database.dbHealth.qCopied'), 'success'))}
                                    sx={{ mt: 0.25 }}
                                  >
                                    <ContentCopy sx={{ fontSize: 14 }} />
                                  </IconButton>
                                </Tooltip>
                              </Stack>
                            )}
                          </Box>
                        ))}
                      </Box>
                    </Paper>
                  )}

                  {/* Blocked processes */}
                  {dbActivity && dbActivity.blockedProcesses.length > 0 && (
                    <Paper variant="outlined" sx={{ p: 2, borderColor: 'error.main' }}>
                      <Typography variant="subtitle2" color="error" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                        {t('database.dbHealth.blockedProcesses')} ({dbActivity.blockedProcesses.length})
                      </Typography>
                      <Box sx={{ maxHeight: dbHealthFullScreen ? undefined : 300, overflow: 'auto' }}>
                        {dbActivity.blockedProcesses.map((bp, i) => (
                          <Box key={i} sx={{ mb: 2, pb: 2, borderBottom: i < dbActivity.blockedProcesses.length - 1 ? '1px solid' : 'none', borderColor: 'divider' }}>
                            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                              <Chip label={t('database.dbHealth.blocked')} size="small" color="error" sx={{ fontSize: '0.65rem', height: 18 }} />
                              <Typography variant="caption" fontWeight={600}>PID {bp.blockedPid}</Typography>
                              <Typography variant="caption" color="text.secondary">{bp.blockedUser}</Typography>
                              <Typography variant="caption" color="text.secondary">{bp.blockedMode}</Typography>
                              {bp.relName && <Typography variant="caption" color="text.secondary">{bp.relName}</Typography>}
                              <Typography variant="caption" sx={{ color: 'error.main', fontWeight: 600 }}>
                                {bp.waitingSeconds > 60 ? `${Math.floor(bp.waitingSeconds / 60)}m ${bp.waitingSeconds % 60}s` : `${bp.waitingSeconds}s`}
                              </Typography>
                            </Stack>
                            <Typography variant="body2" sx={{
                              fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem',
                              bgcolor: 'action.hover', borderRadius: 1, p: 0.75, mb: 1,
                              whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                              display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
                            }}>
                              {bp.blockedQuery}
                            </Typography>
                            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                              <Chip label={t('database.dbHealth.blocking')} size="small" color="warning" sx={{ fontSize: '0.65rem', height: 18 }} />
                              <Typography variant="caption" fontWeight={600}>PID {bp.blockingPid}</Typography>
                              <Typography variant="caption" color="text.secondary">{bp.blockingUser}</Typography>
                              <Typography variant="caption" color="text.secondary">{bp.blockingMode}</Typography>
                            </Stack>
                            <Typography variant="body2" sx={{
                              fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem',
                              bgcolor: 'action.hover', borderRadius: 1, p: 0.75,
                              whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                              display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
                            }}>
                              {bp.blockingQuery}
                            </Typography>
                          </Box>
                        ))}
                      </Box>
                    </Paper>
                  )}

                  {dbActivity && dbActivity.sessions.length === 0 && dbActivity.topUsers.length === 0 && dbActivity.blockedProcesses.length === 0 && (
                    <Alert severity="info">{t('database.dbHealth.noActivity')}</Alert>
                  )}
                </Stack>
              )}

              {/* ===== Top Queries tab ===== */}
              {dbHealthTab === 2 && (
                <Stack spacing={2.5}>
                  {dbActivity && dbActivity.pgStatStatementsAvailable && dbActivity.topQueries.length > 0 && (() => {
                    const sumTotalTime = dbActivity.topQueries.reduce((sum, q) => sum + q.totalTimeMs, 0) || 1
                    return (
                      <TableContainer sx={{ maxHeight: dbHealthFullScreen ? undefined : 500 }}>
                        <Table size="small" stickyHeader>
                          <TableHead>
                            <TableRow>
                              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, width: 40 }} />
                              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, minWidth: 100 }}>
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                  {dialogSortLabel('totalTimeMs', t('database.dbHealth.qLoad'))}
                                  <Tooltip title={t('database.dbHealth.qLoadTooltip')}>
                                    <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                                  </Tooltip>
                                </Box>
                              </TableCell>
                              <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('queryText', t('database.dbHealth.qStatement'))}</TableCell>
                              <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('calls', t('database.dbHealth.qCalls'))}</TableCell>
                              <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('qTotalTime', t('database.dbHealth.qTotalTime'))}</TableCell>
                              <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('meanTimeMs', t('database.dbHealth.qMeanTime'))}</TableCell>
                              <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('rows', t('database.dbHealth.qRows'))}</TableCell>
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {sortedList(dbActivity.topQueries, (q, k) =>
                              k === 'calls' ? q.calls : k === 'totalTimeMs' || k === 'qTotalTime' ? q.totalTimeMs
                              : k === 'meanTimeMs' ? q.meanTimeMs : k === 'rows' ? q.rows : q.queryText
                            ).map((q, i) => {
                              const loadPct = (q.totalTimeMs / sumTotalTime) * 100
                              const isExpanded = expandedQuery === i
                              return (
                                <React.Fragment key={i}>
                                  <TableRow
                                    hover
                                    sx={{ cursor: 'pointer', '& > *': { borderBottom: isExpanded ? 'none' : undefined } }}
                                    onClick={() => {
                                      if (isExpanded) {
                                        setExpandedQuery(null)
                                        setExpandedSqlFull(prev => { const next = new Set(prev); next.delete(i); return next })
                                      } else {
                                        setExpandedQuery(i)
                                      }
                                    }}
                                    selected={isExpanded}
                                  >
                                    <TableCell sx={{ py: 0.5 }}>
                                      <IconButton size="small" sx={{ p: 0 }}>
                                        {isExpanded ? <KeyboardArrowUp sx={{ fontSize: 18 }} /> : <KeyboardArrowDown sx={{ fontSize: 18 }} />}
                                      </IconButton>
                                    </TableCell>
                                    <TableCell sx={{ py: 0.5 }}>
                                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                                        <LinearProgress
                                          variant="determinate"
                                          value={loadPct}
                                          sx={{
                                            width: 60, height: 8, borderRadius: 4, bgcolor: 'grey.200',
                                            '& .MuiLinearProgress-bar': {
                                              borderRadius: 4,
                                              bgcolor: loadPct > 30 ? 'error.main' : loadPct > 10 ? 'warning.main' : 'success.main',
                                            },
                                          }}
                                        />
                                        <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 600, fontSize: '0.7rem', minWidth: 32 }}>
                                          {loadPct.toFixed(loadPct >= 10 ? 0 : 1)}%
                                        </Typography>
                                      </Box>
                                    </TableCell>
                                    <TableCell sx={{ py: 0.5, maxWidth: 300 }}>
                                      <Typography variant="body2" noWrap sx={{
                                        fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem',
                                        maxWidth: 300, display: 'block',
                                      }}>
                                        {q.queryText}
                                      </Typography>
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {q.calls.toLocaleString()}
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {q.totalTimeMs > 1000 ? (q.totalTimeMs / 1000).toFixed(1) + 's' : q.totalTimeMs.toFixed(1) + 'ms'}
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {q.meanTimeMs.toFixed(2)}ms
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {q.rows.toLocaleString()}
                                    </TableCell>
                                  </TableRow>
                                  <TableRow>
                                    <TableCell colSpan={7} sx={{ py: 0, px: 0 }}>
                                      <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                                        <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                                          <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 0.5 }}>
                                            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                                              {t('database.dbHealth.qSqlText')}
                                            </Typography>
                                            <Tooltip title={t('database.dbHealth.qCopy')}>
                                              <IconButton
                                                size="small"
                                                onClick={(e) => {
                                                  e.stopPropagation()
                                                  copyToClipboard(q.queryText).then(() => notify(t('database.dbHealth.qCopied'), 'success'))
                                                }}
                                              >
                                                <ContentCopy sx={{ fontSize: 14 }} />
                                              </IconButton>
                                            </Tooltip>
                                          </Stack>
                                          <Typography variant="body2" sx={{
                                            fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem',
                                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                                            ...(!expandedSqlFull.has(i) && {
                                              display: '-webkit-box',
                                              WebkitLineClamp: 3,
                                              WebkitBoxOrient: 'vertical',
                                              overflow: 'hidden',
                                            }),
                                          }}>
                                            {q.queryText}
                                          </Typography>
                                          {q.queryText.length > 200 && (
                                            <Button
                                              size="small"
                                              sx={{ mt: 0.5, textTransform: 'none', fontSize: '0.7rem', p: 0, minWidth: 'auto' }}
                                              onClick={(e) => {
                                                e.stopPropagation()
                                                setExpandedSqlFull(prev => {
                                                  const next = new Set(prev)
                                                  if (next.has(i)) next.delete(i); else next.add(i)
                                                  return next
                                                })
                                              }}
                                            >
                                              {expandedSqlFull.has(i) ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')}
                                            </Button>
                                          )}
                                        </Box>
                                      </Collapse>
                                    </TableCell>
                                  </TableRow>
                                </React.Fragment>
                              )
                            })}
                          </TableBody>
                        </Table>
                      </TableContainer>
                    )
                  })()}

                  {dbActivity && dbActivity.pgStatStatementsAvailable && queryStatsResetEnabled && (
                    <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <Tooltip title={t('database.dbHealth.resetQueryStatsTooltip')}>
                        <Button
                          size="small"
                          variant="outlined"
                          color="warning"
                          startIcon={<RestartAlt />}
                          onClick={() => setResetStatsConfirmOpen(true)}
                          sx={{ textTransform: 'none', fontSize: '0.75rem' }}
                        >
                          {t('database.dbHealth.resetQueryStats')}
                        </Button>
                      </Tooltip>
                    </Box>
                  )}

                  {dbActivity && dbActivity.pgStatStatementsAvailable && dbActivity.topQueries.length === 0 && (
                    <Alert severity="info">{t('database.dbHealth.noQueries')}</Alert>
                  )}

                  {/* Temp file queries section (on-demand) */}
                  {dbActivity && dbActivity.pgStatStatementsAvailable && (
                    <Paper variant="outlined" sx={{ p: 2 }}>
                      <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                        {t('database.dbHealth.topTempQueries')}
                      </Typography>

                      {!tempFileQueriesLoaded && !tempFileQueriesLoading && (
                        <Button variant="outlined" size="small" onClick={loadTempFileQueries}>
                          {t('database.dbHealth.loadTempQueries')}
                        </Button>
                      )}

                      {tempFileQueriesLoading && (
                        <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
                          <CircularProgress size={24} />
                        </Box>
                      )}

                      {tempFileQueriesLoaded && tempFileQueries.length === 0 && (
                        <Alert severity="info" sx={{ fontSize: '0.8rem' }}>{t('database.dbHealth.noTempQueries')}</Alert>
                      )}

                      {tempFileQueriesLoaded && tempFileQueries.length > 0 && (<>
                        <TableContainer sx={{ maxHeight: dbHealthFullScreen ? undefined : 400 }}>
                          <Table size="small" stickyHeader>
                            <TableHead>
                              <TableRow>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, width: 40 }} />
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.qStatement')}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.qCalls')}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.tempWritten')}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.tempRead')}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.qTotalTime')}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{t('database.dbHealth.qMeanTime')}</TableCell>
                              </TableRow>
                            </TableHead>
                            <TableBody>
                              {tempFileQueries.slice(0, tempQueriesVisible).map((q, i) => {
                                const isExpanded = expandedTempQuery === i
                                return (
                                  <React.Fragment key={i}>
                                    <TableRow
                                      hover
                                      sx={{ cursor: 'pointer', '& > *': { borderBottom: isExpanded ? 'none' : undefined } }}
                                      onClick={() => {
                                        if (isExpanded) {
                                          setExpandedTempQuery(null)
                                          setExpandedTempSqlFull(prev => { const next = new Set(prev); next.delete(i); return next })
                                        } else {
                                          setExpandedTempQuery(i)
                                        }
                                      }}
                                      selected={isExpanded}
                                    >
                                      <TableCell sx={{ py: 0.5 }}>
                                        <IconButton size="small" sx={{ p: 0 }}>
                                          {isExpanded ? <KeyboardArrowUp sx={{ fontSize: 18 }} /> : <KeyboardArrowDown sx={{ fontSize: 18 }} />}
                                        </IconButton>
                                      </TableCell>
                                      <TableCell sx={{ py: 0.5, maxWidth: 300 }}>
                                        <Typography variant="body2" noWrap sx={{
                                          fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem',
                                          maxWidth: 300, display: 'block',
                                        }}>
                                          {q.queryText}
                                        </Typography>
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {q.calls.toLocaleString()}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5, color: 'error.main', fontWeight: 600 }}>
                                        {formatBytes(q.tempBlksWritten * 8192)}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {formatBytes(q.tempBlksRead * 8192)}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {q.totalTimeMs > 1000 ? (q.totalTimeMs / 1000).toFixed(1) + 's' : q.totalTimeMs.toFixed(1) + 'ms'}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {q.meanTimeMs.toFixed(2)}ms
                                      </TableCell>
                                    </TableRow>
                                    <TableRow>
                                      <TableCell colSpan={7} sx={{ py: 0, px: 0 }}>
                                        <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                                          <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                                            <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 0.5 }}>
                                              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
                                                {t('database.dbHealth.qSqlText')}
                                              </Typography>
                                              <Tooltip title={t('database.dbHealth.qCopy')}>
                                                <IconButton
                                                  size="small"
                                                  onClick={(e) => {
                                                    e.stopPropagation()
                                                    copyToClipboard(q.queryText).then(() => notify(t('database.dbHealth.qCopied'), 'success'))
                                                  }}
                                                >
                                                  <ContentCopy sx={{ fontSize: 14 }} />
                                                </IconButton>
                                              </Tooltip>
                                            </Stack>
                                            <Typography variant="body2" sx={{
                                              fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem',
                                              whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                                              ...(!expandedTempSqlFull.has(i) && {
                                                display: '-webkit-box',
                                                WebkitLineClamp: 3,
                                                WebkitBoxOrient: 'vertical',
                                                overflow: 'hidden',
                                              }),
                                            }}>
                                              {q.queryText}
                                            </Typography>
                                            {q.queryText.length > 200 && (
                                              <Button
                                                size="small"
                                                sx={{ mt: 0.5, textTransform: 'none', fontSize: '0.7rem', p: 0, minWidth: 'auto' }}
                                                onClick={(e) => {
                                                  e.stopPropagation()
                                                  setExpandedTempSqlFull(prev => {
                                                    const next = new Set(prev)
                                                    if (next.has(i)) next.delete(i); else next.add(i)
                                                    return next
                                                  })
                                                }}
                                              >
                                                {expandedTempSqlFull.has(i) ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')}
                                              </Button>
                                            )}
                                          </Box>
                                        </Collapse>
                                      </TableCell>
                                    </TableRow>
                                  </React.Fragment>
                                )
                              })}
                            </TableBody>
                          </Table>
                        </TableContainer>
                        {tempFileQueries.length > 10 && (
                          <Box sx={{ textAlign: 'center', mt: 1 }}>
                            <Button size="small" onClick={() => setTempQueriesVisible(prev => prev >= tempFileQueries.length ? 10 : prev + 10)}>
                              {tempQueriesVisible >= tempFileQueries.length ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')} ({Math.max(0, tempFileQueries.length - tempQueriesVisible)})
                            </Button>
                          </Box>
                        )}
                      </>)}
                    </Paper>
                  )}

                  {dbActivity && !dbActivity.pgStatStatementsAvailable && (
                    <Alert
                      severity="info"
                      variant="outlined"
                      sx={{ fontSize: '0.8rem' }}
                      action={
                        <Button color="info" size="small" variant="outlined" onClick={() => setPgssConfirmOpen(true)}>
                          {t('database.dbHealth.startMonitoring')}
                        </Button>
                      }
                    >
                      {t('database.dbHealth.pgssNotAvailable')}
                    </Alert>
                  )}
                </Stack>
              )}

              {/* ===== Tables tab ===== */}
              {dbHealthTab === 3 && (
                <Stack spacing={2.5}>
                  {/* Top tables by size */}
                  {dbTableStats && dbTableStats.tables.length > 0 && (() => {
                    const maxTableSize = Math.max(...dbTableStats.tables.map(t => t.totalSizeBytes), 1)
                    return (
                      <Paper variant="outlined" sx={{ p: 2 }}>
                        <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                          {t('database.dbHealth.topTables')} ({dbTableStats.tables.length})
                        </Typography>
                        {dbTableStats.statsResetAt && (
                          <Alert severity="info" variant="outlined" sx={{ mb: 1.5, py: 0, fontSize: '0.75rem' }}>
                            {t('database.dbHealth.statsResetAt', { date: dbTableStats.statsResetAt.substring(0, 16) })}
                          </Alert>
                        )}
                        <TableContainer sx={{ maxHeight: dbHealthFullScreen ? undefined : 450 }}>
                          <Table size="small" stickyHeader>
                            <TableHead>
                              <TableRow>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('tableName', t('database.dbHealth.tblName'))}</TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, minWidth: 120 }}>{dialogSortLabel('totalSizeBytes', t('database.dbHealth.tblTotalSize'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('tableSizeBytes', t('database.dbHealth.tblTableSize'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('indexSizeBytes', t('database.dbHealth.tblIndexSize'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('liveTuples', t('database.dbHealth.tblLiveRows'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('deadTuples', t('database.dbHealth.tblDeadRows'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('idxRatio', t('database.dbHealth.tblIdxRatio'))}
                                    <Tooltip title={t('database.dbHealth.tblIdxRatioTooltip')}>
                                      <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                                    </Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                    {dialogSortLabel('lastVacuum', t('database.dbHealth.tblLastVacuum'))}
                                    <Tooltip title={t('database.dbHealth.tblLastVacuumTooltip')}>
                                      <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                                    </Tooltip>
                                  </Box>
                                </TableCell>
                                {queryStatsResetEnabled && (
                                  <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, width: 40 }} />
                                )}
                              </TableRow>
                            </TableHead>
                            <TableBody>
                              {(() => {
                                const sorted = sortedList(dbTableStats.tables, (tbl, k) => {
                                  if (k === 'tableName') return tbl.tableName
                                  if (k === 'totalSizeBytes') return tbl.totalSizeBytes
                                  if (k === 'tableSizeBytes') return tbl.tableSizeBytes
                                  if (k === 'indexSizeBytes') return tbl.indexSizeBytes
                                  if (k === 'liveTuples') return tbl.liveTuples
                                  if (k === 'deadTuples') return tbl.deadTuples
                                  if (k === 'idxRatio') { const t = tbl.seqScan + tbl.idxScan; return t > 0 ? tbl.idxScan / t : -1 }
                                  if (k === 'lastVacuum') return tbl.lastAutoVacuum || tbl.lastVacuum || ''
                                  return 0
                                })
                                return sorted.slice(0, tablesVisible).map((tbl) => {
                                const totalScans = tbl.seqScan + tbl.idxScan
                                const idxRatio = totalScans > 0 ? (tbl.idxScan / totalScans) * 100 : -1
                                const sizePct = (tbl.totalSizeBytes / maxTableSize) * 100
                                const deadRatio = tbl.liveTuples + tbl.deadTuples > 0
                                  ? tbl.deadTuples / (tbl.liveTuples + tbl.deadTuples) * 100 : 0
                                const lastVac = tbl.lastAutoVacuum || tbl.lastVacuum
                                return (
                                  <TableRow key={`${tbl.schemaName}.${tbl.tableName}`} hover>
                                    <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {tbl.schemaName !== 'public' && (
                                        <Typography component="span" variant="caption" color="text.secondary">{tbl.schemaName}.</Typography>
                                      )}
                                      {tbl.tableName}
                                    </TableCell>
                                    <TableCell sx={{ py: 0.5 }}>
                                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                                        <LinearProgress
                                          variant="determinate"
                                          value={sizePct}
                                          sx={{
                                            width: 50, height: 6, borderRadius: 3, bgcolor: 'grey.200',
                                            '& .MuiLinearProgress-bar': { borderRadius: 3 },
                                          }}
                                        />
                                        <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', whiteSpace: 'nowrap' }}>
                                          {formatBytes(tbl.totalSizeBytes)}
                                        </Typography>
                                      </Box>
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {formatBytes(tbl.tableSizeBytes)}
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {formatBytes(tbl.indexSizeBytes)}
                                    </TableCell>
                                    <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                      {tbl.liveTuples.toLocaleString()}
                                    </TableCell>
                                    <TableCell align="right" sx={{
                                      fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5,
                                      color: deadRatio > 20 ? 'error.main' : deadRatio > 5 ? 'warning.main' : 'text.primary',
                                    }}>
                                      {tbl.deadTuples.toLocaleString()}
                                      {deadRatio > 5 && (
                                        <Typography component="span" variant="caption" sx={{ ml: 0.5 }}>
                                          ({deadRatio.toFixed(0)}%)
                                        </Typography>
                                      )}
                                    </TableCell>
                                    <TableCell align="right" sx={{
                                      fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5,
                                      color: idxRatio < 0 ? 'text.secondary' : idxRatio >= 90 ? 'success.main' : idxRatio >= 50 ? 'warning.main' : 'error.main',
                                    }}>
                                      {idxRatio < 0 ? '-' : `${idxRatio.toFixed(0)}%`}
                                    </TableCell>
                                    <TableCell sx={{ fontSize: '0.7rem', py: 0.5, color: 'text.secondary', whiteSpace: 'nowrap' }}>
                                      {lastVac ? lastVac.substring(0, 16).replace('T', ' ') : '-'}
                                    </TableCell>
                                    {queryStatsResetEnabled && (
                                      <TableCell sx={{ py: 0.5, px: 0.5 }}>
                                        <Tooltip title={t('database.dbHealth.resetSingleTableStats')}>
                                          <IconButton
                                            size="small"
                                            color="warning"
                                            onClick={() => setResetSingleTableTarget({ schemaName: tbl.schemaName, tableName: tbl.tableName })}
                                            sx={{ p: 0.25 }}
                                          >
                                            <RestartAlt sx={{ fontSize: 16 }} />
                                          </IconButton>
                                        </Tooltip>
                                      </TableCell>
                                    )}
                                  </TableRow>
                                )
                              })})()}
                            </TableBody>
                          </Table>
                        </TableContainer>
                        {dbTableStats.tables.length > 20 && (
                          <Box sx={{ textAlign: 'center', mt: 1 }}>
                            <Button size="small" onClick={() => setTablesVisible(prev => prev >= dbTableStats!.tables.length ? 20 : prev + 20)}>
                              {tablesVisible >= dbTableStats.tables.length ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')} ({Math.max(0, dbTableStats.tables.length - tablesVisible)})
                            </Button>
                          </Box>
                        )}
                      </Paper>
                    )
                  })()}

                  {dbTableStats && queryStatsResetEnabled && (
                    <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <Tooltip title={t('database.dbHealth.resetTableStatsTooltip')}>
                        <Button
                          size="small"
                          variant="outlined"
                          color="warning"
                          startIcon={<RestartAlt />}
                          onClick={() => setResetTableStatsConfirmOpen(true)}
                          sx={{ textTransform: 'none', fontSize: '0.75rem' }}
                        >
                          {t('database.dbHealth.resetTableStats')}
                        </Button>
                      </Tooltip>
                    </Box>
                  )}

                  {dbTableStats && dbTableStats.tables.length === 0 && (
                    <Alert severity="info">{t('database.dbHealth.noTables')}</Alert>
                  )}
                </Stack>
              )}

              {/* ===== Indexes tab ===== */}
              {dbHealthTab === 4 && (
                <Stack spacing={2.5}>
                  {/* Summary bar */}
                  {dbTableStats && (dbTableStats.usedIndexes.length > 0 || dbTableStats.unusedIndexes.length > 0) && (() => {
                    const totalUsed = dbTableStats.usedIndexes.length
                    const totalUnused = dbTableStats.unusedIndexes.length
                    const totalAll = totalUsed + totalUnused
                    const usedSize = dbTableStats.usedIndexes.reduce((s, idx) => s + idx.sizeBytes, 0)
                    const unusedSize = dbTableStats.unusedIndexes.reduce((s, idx) => s + idx.sizeBytes, 0)
                    const healthPct = totalAll > 0 ? (totalUsed / totalAll) * 100 : 100
                    const healthColor = healthPct >= 90 ? 'success.main' : healthPct >= 70 ? 'warning.main' : 'error.main'
                    return (
                      <Paper variant="outlined" sx={{ p: 2 }}>
                        <Stack direction="row" alignItems="center" justifyContent="space-evenly">
                          {/* Total */}
                          <Box sx={{ textAlign: 'center' }}>
                            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.6rem', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{t('database.dbHealth.idxSummaryTotal')}</Typography>
                            <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '1.1rem', lineHeight: 1.2 }}>{totalAll}</Typography>
                            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem' }}>{formatBytes(usedSize + unusedSize)}</Typography>
                          </Box>
                          {/* Used */}
                          <Box sx={{ textAlign: 'center' }}>
                            <Typography variant="caption" color="success.main" sx={{ fontSize: '0.6rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600 }}>{t('database.dbHealth.idxSummaryUsed')}</Typography>
                            <Typography color="success.main" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '1.1rem', lineHeight: 1.2 }}>{totalUsed}</Typography>
                            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem' }}>{formatBytes(usedSize)}</Typography>
                          </Box>
                          {/* Donut */}
                          <Box sx={{ position: 'relative', width: 68, height: 68, flexShrink: 0 }}>
                            <svg viewBox="0 0 36 36" width="68" height="68">
                              <circle cx="18" cy="18" r="14" fill="none" stroke="currentColor" strokeWidth="3.5" opacity={0.1} />
                              <circle cx="18" cy="18" r="14" fill="none"
                                stroke={healthPct >= 90 ? '#2e7d32' : healthPct >= 70 ? '#ed6c02' : '#d32f2f'}
                                strokeWidth="3.5" strokeDasharray={`${healthPct * 0.88} 88`}
                                strokeLinecap="round" transform="rotate(-90 18 18)" />
                            </svg>
                            <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
                              <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '0.8rem', lineHeight: 1, color: healthColor }}>{healthPct.toFixed(0)}%</Typography>
                            </Box>
                          </Box>
                          {/* Unused */}
                          <Box sx={{ textAlign: 'center' }}>
                            <Typography variant="caption" sx={{ fontSize: '0.6rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600, color: totalUnused > 0 ? 'warning.main' : 'text.secondary' }}>{t('database.dbHealth.idxSummaryUnused')}</Typography>
                            <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '1.1rem', lineHeight: 1.2, color: totalUnused > 0 ? 'warning.main' : 'text.primary' }}>{totalUnused}</Typography>
                            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem' }}>{formatBytes(unusedSize)}</Typography>
                          </Box>
                          {/* Wasted */}
                          <Box sx={{ textAlign: 'center' }}>
                            <Typography variant="caption" color="error.main" sx={{ fontSize: '0.6rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600 }}>{t('database.dbHealth.idxImpactWasted')}</Typography>
                            <Typography color="error.main" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '1.1rem', lineHeight: 1.2 }}>{formatBytes(unusedSize)}</Typography>
                          </Box>
                        </Stack>
                      </Paper>
                    )
                  })()}

                  {dbTableStats?.statsResetAt && (
                    <Alert severity="info" variant="outlined" sx={{ py: 0, fontSize: '0.75rem' }}>
                      {t('database.dbHealth.statsResetAt', { date: dbTableStats.statsResetAt.substring(0, 16) })}
                    </Alert>
                  )}

                  {/* Write Overhead (impact) — most actionable, shown first */}
                  {dbTableStats && dbTableStats.indexImpact && dbTableStats.indexImpact.length > 0 && (() => {
                    const totalWasted = dbTableStats.indexImpact.reduce((s, r) => s + r.wastedBytes, 0)
                    return (
                      <Paper variant="outlined" sx={{ p: 2 }}>
                        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5 }}>
                          <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                            {t('database.dbHealth.idxImpact')} ({dbTableStats.indexImpact.length})
                          </Typography>
                          <Chip label={formatBytes(totalWasted)} size="small" color="error" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
                          <Tooltip title={t('database.dbHealth.idxImpactTooltip')}>
                            <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                          </Tooltip>
                        </Stack>
                        <TableContainer>
                          <Table size="small">
                            <TableHead>
                              <TableRow>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('tableName', t('database.dbHealth.idxImpactTable'))}</TableCell>
                                <TableCell align="center" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('unusedIndexes', t('database.dbHealth.idxImpactCount'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('wastedBytes', t('database.dbHealth.idxImpactWasted'))}
                                    <Tooltip title={t('database.dbHealth.idxImpactWastedTooltip')}><InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} /></Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('seqScans', t('database.dbHealth.idxImpactSeqScans'))}
                                    <Tooltip title={t('database.dbHealth.idxImpactSeqScansTooltip')}><InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} /></Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('idxScans', t('database.dbHealth.idxImpactIdxScans'))}
                                    <Tooltip title={t('database.dbHealth.idxImpactIdxScansTooltip')}><InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} /></Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('totalWrites', t('database.dbHealth.idxImpactWrites'))}
                                    <Tooltip title={t('database.dbHealth.idxImpactWritesTooltip')}><InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} /></Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
                                    {dialogSortLabel('overhead', t('database.dbHealth.idxImpactOverhead'))}
                                    <Tooltip title={t('database.dbHealth.idxImpactOverheadTooltip')}><InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} /></Tooltip>
                                  </Box>
                                </TableCell>
                              </TableRow>
                            </TableHead>
                            <TableBody>
                              {sortedList(dbTableStats.indexImpact, (row, k) =>
                                k === 'tableName' ? row.tableName : k === 'unusedIndexes' ? row.unusedIndexes
                                : k === 'wastedBytes' ? row.wastedBytes : k === 'seqScans' ? row.seqScans
                                : k === 'idxScans' ? row.idxScans : k === 'totalWrites' ? row.totalWrites
                                : k === 'overhead' ? row.unusedIndexes * 2.5 : row.wastedBytes
                              ).map((row) => {
                                const overheadPct = row.unusedIndexes * 2.5
                                const isExpanded = expandedImpact === row.tableName
                                const cachedQueries = impactQueries.get(row.tableName)
                                const isLoadingThis = impactQueriesLoading === row.tableName
                                return (
                                  <React.Fragment key={row.tableName}>
                                    <TableRow
                                      hover
                                      sx={{ cursor: 'pointer', '& > *': { borderBottom: isExpanded ? 'none' : undefined } }}
                                      selected={isExpanded}
                                      onClick={() => {
                                        if (isExpanded) {
                                          impactAbortRef.current?.abort()
                                          setExpandedImpact(null)
                                        } else {
                                          setExpandedImpact(row.tableName)
                                          setExpandedImpactQuery(null)
                                          if (!cachedQueries && dbHealthTarget) {
                                            impactAbortRef.current?.abort()
                                            const controller = new AbortController()
                                            impactAbortRef.current = controller
                                            setImpactQueriesLoading(row.tableName)
                                            getTopQueriesForTable(currentRepo, dbHealthTarget.name, row.tableName, controller.signal)
                                              .then((queries) => {
                                                setImpactQueries(prev => new Map(prev).set(row.tableName, queries))
                                                setImpactQueriesLoading(null)
                                              })
                                              .catch(() => setImpactQueriesLoading(null))
                                          }
                                        }
                                      }}
                                    >
                                      <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                          <IconButton size="small" sx={{ p: 0 }}>
                                            {isExpanded ? <KeyboardArrowUp sx={{ fontSize: 18 }} /> : <KeyboardArrowDown sx={{ fontSize: 18 }} />}
                                          </IconButton>
                                          {row.tableName}
                                        </Box>
                                      </TableCell>
                                      <TableCell align="center" sx={{ py: 0.5 }}>
                                        <Chip label={row.unusedIndexes} size="small" color={row.unusedIndexes >= 5 ? 'error' : row.unusedIndexes >= 3 ? 'warning' : 'default'} sx={{ fontSize: '0.7rem', height: 20, minWidth: 28 }} />
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5, fontWeight: 600 }}>
                                        {formatBytes(row.wastedBytes)}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5, color: row.seqScans > row.idxScans ? 'warning.main' : 'text.primary' }}>
                                        {row.seqScans.toLocaleString()}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {row.idxScans.toLocaleString()}
                                      </TableCell>
                                      <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                        {row.totalWrites.toLocaleString()}
                                      </TableCell>
                                      <TableCell align="right" sx={{ py: 0.5 }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.75 }}>
                                          <LinearProgress variant="determinate" value={Math.min(overheadPct, 100)} sx={{ width: 50, height: 6, borderRadius: 3, bgcolor: 'grey.200', '& .MuiLinearProgress-bar': { borderRadius: 3, bgcolor: overheadPct > 15 ? 'error.main' : overheadPct > 7 ? 'warning.main' : 'success.main' } }} />
                                          <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 600, fontSize: '0.7rem', minWidth: 32, color: overheadPct > 15 ? 'error.main' : overheadPct > 7 ? 'warning.main' : 'text.secondary' }}>
                                            ~{overheadPct.toFixed(0)}%
                                          </Typography>
                                        </Box>
                                      </TableCell>
                                    </TableRow>
                                    <TableRow>
                                      <TableCell colSpan={7} sx={{ py: 0, px: 0 }}>
                                        <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                                          <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                                            {!dbActivity?.pgStatStatementsAvailable ? (
                                              <Typography variant="caption" color="text.secondary">{t('database.dbHealth.idxImpactPgssRequired')}</Typography>
                                            ) : isLoadingThis ? (
                                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                                <CircularProgress size={16} />
                                                <Typography variant="caption" color="text.secondary">Loading...</Typography>
                                              </Box>
                                            ) : cachedQueries && cachedQueries.length > 0 ? (
                                              <>
                                                <Typography variant="caption" fontWeight={600} color="text.secondary" sx={{ display: 'block', mb: 1, textTransform: 'uppercase', fontSize: '0.6rem', letterSpacing: '0.05em' }}>
                                                  {t('database.dbHealth.idxImpactTopQueries')}
                                                </Typography>
                                                <Table size="small">
                                                  <TableHead>
                                                    <TableRow>
                                                      <TableCell sx={{ fontWeight: 600, fontSize: '0.65rem', py: 0.25 }}>SQL</TableCell>
                                                      <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.65rem', py: 0.25 }}>{t('database.dbHealth.qCalls')}</TableCell>
                                                      <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.65rem', py: 0.25 }}>{t('database.dbHealth.qTotalTime')}</TableCell>
                                                      <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.65rem', py: 0.25 }}>{t('database.dbHealth.qMeanTime')}</TableCell>
                                                      <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.65rem', py: 0.25 }}>{t('database.dbHealth.qRows')}</TableCell>
                                                    </TableRow>
                                                  </TableHead>
                                                  <TableBody>
                                                    {cachedQueries.map((q, qi) => {
                                                      const isQueryExpanded = expandedImpactQuery === qi
                                                      return (
                                                        <React.Fragment key={qi}>
                                                          <TableRow hover sx={{ cursor: 'pointer', '& > *': { borderBottom: isQueryExpanded ? 'none' : undefined } }} onClick={(e) => { e.stopPropagation(); setExpandedImpactQuery(isQueryExpanded ? null : qi) }}>
                                                            <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', py: 0.25, maxWidth: 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                                                <IconButton size="small" sx={{ p: 0 }}>{isQueryExpanded ? <KeyboardArrowUp sx={{ fontSize: 14 }} /> : <KeyboardArrowDown sx={{ fontSize: 14 }} />}</IconButton>
                                                                {q.queryText}
                                                              </Box>
                                                            </TableCell>
                                                            <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', py: 0.25 }}>{q.calls.toLocaleString()}</TableCell>
                                                            <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', py: 0.25 }}>{q.totalTimeMs >= 1000 ? (q.totalTimeMs / 1000).toFixed(1) + 's' : q.totalTimeMs.toFixed(1) + 'ms'}</TableCell>
                                                            <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', py: 0.25 }}>{q.meanTimeMs.toFixed(2)}ms</TableCell>
                                                            <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', py: 0.25 }}>{q.rows.toLocaleString()}</TableCell>
                                                          </TableRow>
                                                          <TableRow>
                                                            <TableCell colSpan={5} sx={{ py: 0, px: 0 }}>
                                                              <Collapse in={isQueryExpanded} timeout="auto" unmountOnExit>
                                                                <Box sx={{ p: 1.5, bgcolor: 'background.default', display: 'flex', alignItems: 'flex-start', gap: 1 }}>
                                                                  <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem', whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1 }}>{q.queryText}</Typography>
                                                                  <Tooltip title={t('database.dbHealth.qCopied')}>
                                                                    <IconButton size="small" onClick={(e) => { e.stopPropagation(); copyToClipboard(q.queryText).then(() => notify(t('database.dbHealth.qCopied'), 'success')) }}><ContentCopy sx={{ fontSize: 14 }} /></IconButton>
                                                                  </Tooltip>
                                                                </Box>
                                                              </Collapse>
                                                            </TableCell>
                                                          </TableRow>
                                                        </React.Fragment>
                                                      )
                                                    })}
                                                  </TableBody>
                                                </Table>
                                              </>
                                            ) : (
                                              <Typography variant="caption" color="text.secondary">{t('database.dbHealth.idxImpactNoQueries')}</Typography>
                                            )}
                                          </Box>
                                        </Collapse>
                                      </TableCell>
                                    </TableRow>
                                  </React.Fragment>
                                )
                              })}
                            </TableBody>
                          </Table>
                        </TableContainer>
                      </Paper>
                    )
                  })()}

                  {/* Unused indexes */}
                  {dbTableStats && dbTableStats.unusedIndexes.length > 0 && (() => {
                    const allSorted = sortedList(dbTableStats.unusedIndexes, (idx, k) =>
                      k === 'indexName' ? idx.indexName : k === 'tableName' ? idx.tableName : k === 'sizeBytes' ? idx.sizeBytes : 0
                    )
                    const totalWasted = allSorted.reduce((s, idx) => s + idx.sizeBytes, 0)
                    const visible = allSorted.slice(0, unusedIdxVisible)
                    const hasMore = allSorted.length > 30
                    return (
                      <Paper variant="outlined" sx={{ p: 2 }}>
                        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5 }}>
                          <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                            {t('database.dbHealth.unusedIndexes')} ({allSorted.length})
                          </Typography>
                          <Chip label={formatBytes(totalWasted)} size="small" color="warning" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
                        </Stack>
                        <TableContainer sx={{ maxHeight: dbHealthFullScreen ? undefined : 350 }}>
                          <Table size="small" stickyHeader>
                            <TableHead>
                              <TableRow>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('indexName', t('database.dbHealth.idxName'))}</TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('tableName', t('database.dbHealth.tblName'))}</TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('sizeBytes', t('database.dbColumns.size'))}</TableCell>
                              </TableRow>
                            </TableHead>
                            <TableBody>
                              {visible.map((idx) => (
                                <TableRow key={`${idx.schemaName}.${idx.indexName}`} hover>
                                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>{idx.indexName}</TableCell>
                                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5, color: 'text.secondary' }}>{idx.tableName}</TableCell>
                                  <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>{formatBytes(idx.sizeBytes)}</TableCell>
                                </TableRow>
                              ))}
                            </TableBody>
                          </Table>
                        </TableContainer>
                        {allSorted.length > 30 && (
                          <Box sx={{ textAlign: 'center', mt: 1 }}>
                            <Button size="small" onClick={() => setUnusedIdxVisible(prev => prev >= allSorted.length ? 30 : prev + 30)}>
                              {unusedIdxVisible >= allSorted.length ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')} ({Math.max(0, allSorted.length - unusedIdxVisible)})
                            </Button>
                          </Box>
                        )}
                      </Paper>
                    )
                  })()}

                  {/* Used indexes */}
                  {dbTableStats && dbTableStats.usedIndexes.length > 0 && (() => {
                    const maxScans = Math.max(...dbTableStats.usedIndexes.map(idx => idx.idxScan), 1)
                    const totalUsedSize = dbTableStats.usedIndexes.reduce((s, idx) => s + idx.sizeBytes, 0)
                    return (
                      <Paper variant="outlined" sx={{ p: 2 }}>
                        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5 }}>
                          <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                            {t('database.dbHealth.usedIndexes')} ({dbTableStats.usedIndexes.length})
                          </Typography>
                          <Chip label={formatBytes(totalUsedSize)} size="small" color="success" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
                        </Stack>
                        <TableContainer sx={{ maxHeight: dbHealthFullScreen ? undefined : 350 }}>
                          <Table size="small" stickyHeader>
                            <TableHead>
                              <TableRow>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('indexName', t('database.dbHealth.idxName'))}</TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('tableName', t('database.dbHealth.tblName'))}</TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                    {dialogSortLabel('idxType', t('database.dbHealth.idxType'))}
                                    <Tooltip title={t('database.dbHealth.idxTypeTooltip')}>
                                      <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                                    </Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5, minWidth: 120 }}>
                                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                    {dialogSortLabel('idxScan', t('database.dbHealth.idxScans'))}
                                    <Tooltip title={t('database.dbHealth.idxScansTooltip')}>
                                      <InfoOutlined sx={{ fontSize: 14, color: 'text.secondary', cursor: 'help' }} />
                                    </Tooltip>
                                  </Box>
                                </TableCell>
                                <TableCell align="right" sx={{ fontWeight: 600, fontSize: '0.7rem', py: 0.5 }}>{dialogSortLabel('sizeBytes', t('database.dbColumns.size'))}</TableCell>
                              </TableRow>
                            </TableHead>
                            <TableBody>
                              {(() => {
                                const sorted = sortedList(dbTableStats.usedIndexes, (idx, k) =>
                                  k === 'indexName' ? idx.indexName : k === 'tableName' ? idx.tableName
                                  : k === 'idxType' ? (idx.isPrimary ? 'a' : idx.isUnique ? 'b' : 'c')
                                  : k === 'idxScan' ? idx.idxScan : k === 'sizeBytes' ? idx.sizeBytes : 0
                                )
                                return sorted.slice(0, usedIdxVisible).map((idx) => (
                                <TableRow key={`${idx.schemaName}.${idx.indexName}`} hover>
                                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                    {idx.indexName}
                                  </TableCell>
                                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5, color: 'text.secondary' }}>
                                    {idx.tableName}
                                  </TableCell>
                                  <TableCell sx={{ py: 0.5 }}>
                                    {idx.isPrimary ? <Chip label="PK" size="small" color="primary" variant="outlined" sx={{ fontSize: '0.65rem', height: 18 }} />
                                      : idx.isUnique ? <Chip label="UQ" size="small" color="info" variant="outlined" sx={{ fontSize: '0.65rem', height: 18 }} />
                                      : <Chip label="IDX" size="small" variant="outlined" sx={{ fontSize: '0.65rem', height: 18 }} />}
                                  </TableCell>
                                  <TableCell sx={{ py: 0.5 }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                                      <LinearProgress
                                        variant="determinate"
                                        value={(idx.idxScan / maxScans) * 100}
                                        sx={{
                                          width: 50, height: 6, borderRadius: 3, bgcolor: 'grey.200',
                                          '& .MuiLinearProgress-bar': { borderRadius: 3, bgcolor: 'success.main' },
                                        }}
                                      />
                                      <Typography variant="caption" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.7rem' }}>
                                        {idx.idxScan.toLocaleString()}
                                      </Typography>
                                    </Box>
                                  </TableCell>
                                  <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.75rem', py: 0.5 }}>
                                    {formatBytes(idx.sizeBytes)}
                                  </TableCell>
                                </TableRow>
                              ))})()}
                            </TableBody>
                          </Table>
                        </TableContainer>
                        {dbTableStats.usedIndexes.length > 30 && (
                          <Box sx={{ textAlign: 'center', mt: 1 }}>
                            <Button size="small" onClick={() => setUsedIdxVisible(prev => prev >= dbTableStats!.usedIndexes.length ? 30 : prev + 30)}>
                              {usedIdxVisible >= dbTableStats.usedIndexes.length ? t('database.dbHealth.showLess') : t('database.dbHealth.showMore')} ({Math.max(0, dbTableStats.usedIndexes.length - usedIdxVisible)})
                            </Button>
                          </Box>
                        )}
                      </Paper>
                    )
                  })()}

                  {dbTableStats && dbTableStats.usedIndexes.length === 0 && dbTableStats.unusedIndexes.length === 0 && (
                    <Alert severity="info">{t('database.dbHealth.noIndexes')}</Alert>
                  )}
                </Stack>
              )}
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDbHealthOpen(false)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>

      {/* Force delete confirmation (active connections warning) */}
      <Dialog open={forceDeleteConfirm !== null} onClose={() => setForceDeleteConfirm(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <Warning sx={{ mr: 1, verticalAlign: 'middle' }} />
          {t('database.forceDeleteTitle')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <span dangerouslySetInnerHTML={{ __html: t('database.forceDeleteMessage', {
              name: forceDeleteConfirm?.db.name ?? '',
              count: forceDeleteConfirm?.activeConnections ?? 0,
            }) }} />
          </Alert>
          <Typography variant="body2" color="text.secondary">
            {t('database.forceDeleteHint')}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setForceDeleteConfirm(null)}>{t('common.cancel')}</Button>
          <Button variant="contained" color="error" onClick={handleForceDelete}>
            {t('database.forceDeleteConfirm')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Description edit confirmation */}
      <PasswordConfirmDialog
        open={descPasswordOpen}
        onClose={() => { setDescPasswordOpen(false); cancelEditDesc() }}
        onConfirm={handleDescSave}
        title={t('database.editMetadataTitle')}
        message={t('database.editMetadataPasswordMessage')}
        confirmLabel={t('common.save')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Edit />}
      />

      {/* Create Snapshot */}
      <CreateSnapshotModal
        open={snapshotTarget !== null}
        onClose={() => setSnapshotTarget(null)}
        onCreated={() => { setSnapshotTarget(null); loadDatabases() }}
        initialRepository={currentRepo}
        initialDatabase={snapshotTarget?.name}
      />

      {/* Restore — Password + Dump Browser + Restore Modal */}
      <PasswordConfirmDialog
        open={restorePasswordOpen}
        onClose={() => { setRestorePasswordOpen(false); setRestoreTarget(null) }}
        onConfirm={handleRestorePasswordConfirm}
        title={t('common.restore')}
        message={t('database.restoreConfirmMessage', { name: restoreTarget?.name ?? '' })}
        confirmLabel={t('common.restore')}
        confirmColor="success"
        icon={<Restore />}
      />
      <DumpBrowserModal
        open={dumpBrowserOpen}
        dumps={browseDumps}
        onClose={() => setDumpBrowserOpen(false)}
        onSelect={handleDumpSelected}
        onSelectSnapshot={handleSnapshotSelected}
      />
      <RestoreDumpModal
        open={restoreOpen}
        dump={restoreDump}
        snapshot={restoreSnapshot}
        initialTargetDatabase={restoreTarget?.name}
        onClose={() => { setRestoreOpen(false); setRestoreDump(null); setRestoreSnapshot(null) }}
        onRestored={() => { setRestoreOpen(false); setRestoreDump(null); setRestoreSnapshot(null); loadDatabases() }}
      />

      {/* Run Migration */}
      {migrationTarget && (
        <RunMigrationModal
          open={migrationTarget !== null}
          repository={currentRepo}
          databaseName={migrationTarget.name}
          onClose={() => setMigrationTarget(null)}
          onCompleted={() => { setMigrationTarget(null); loadDatabases() }}
        />
      )}

      {/* Query Runner */}
      <QueryRunnerDialog
        open={queryTarget !== null}
        database={queryTarget}
        repository={currentRepo}
        writeEnabled={queryWriteEnabled}
        onClose={() => setQueryTarget(null)}
      />

      {/* Enable pg_stat_statements confirmation */}
      <PasswordConfirmDialog
        open={pgssConfirmOpen}
        onClose={() => setPgssConfirmOpen(false)}
        onConfirm={handleEnablePgss}
        title={t('database.dbHealth.startMonitoring')}
        message={t('database.dbHealth.pgssEnableConfirm', { name: dbHealthTarget?.name ?? '' })}
        confirmLabel={t('database.dbHealth.startMonitoring')}
        confirmColor="primary"
        icon={<Speed />}
      />

      {/* Reset query stats confirmation */}
      <PasswordConfirmDialog
        open={resetStatsConfirmOpen}
        onClose={() => setResetStatsConfirmOpen(false)}
        onConfirm={handleResetQueryStats}
        title={t('database.dbHealth.resetQueryStats')}
        message={t('database.dbHealth.resetQueryStatsConfirm', { name: dbHealthTarget?.name ?? '' })}
        confirmLabel={t('database.dbHealth.resetQueryStats')}
        loadingLabel={t('database.dbHealth.resettingQueryStats')}
        confirmColor="warning"
        icon={<RestartAlt />}
      />

      {/* Reset all table/index stats confirmation */}
      <PasswordConfirmDialog
        open={resetTableStatsConfirmOpen}
        onClose={() => setResetTableStatsConfirmOpen(false)}
        onConfirm={handleResetTableStats}
        title={t('database.dbHealth.resetTableStats')}
        message={t('database.dbHealth.resetTableStatsConfirm', { name: dbHealthTarget?.name ?? '' })}
        confirmLabel={t('database.dbHealth.resetTableStats')}
        loadingLabel={t('database.dbHealth.resettingQueryStats')}
        confirmColor="warning"
        icon={<RestartAlt />}
      />

      {/* Reset single table stats confirmation */}
      <PasswordConfirmDialog
        open={resetSingleTableTarget !== null}
        onClose={() => setResetSingleTableTarget(null)}
        onConfirm={handleResetSingleTableStats}
        title={t('database.dbHealth.resetSingleTableStats')}
        message={t('database.dbHealth.resetSingleTableStatsConfirm', {
          table: resetSingleTableTarget ? `${resetSingleTableTarget.schemaName}.${resetSingleTableTarget.tableName}` : '',
          name: dbHealthTarget?.name ?? '',
        })}
        confirmLabel={t('database.dbHealth.resetSingleTableStats')}
        loadingLabel={t('database.dbHealth.resettingQueryStats')}
        confirmColor="warning"
        icon={<RestartAlt />}
      />

      {/* Server Health dialog */}
      <Dialog open={healthOpen} onClose={() => { setHealthOpen(false); setServerHealthFullScreen(false) }} maxWidth="md" fullWidth fullScreen={serverHealthFullScreen}>
        <DialogTitle sx={{ bgcolor: 'info.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <MonitorHeart sx={{ mr: 1 }} />
          <Box sx={{ flex: 1 }}>
            {t('database.dbHealth.title')}
            {repositories.length > 1 && (
              <Typography component="span" variant="body2" sx={{ ml: 1, opacity: 0.8 }}>
                ({currentRepo})
              </Typography>
            )}
          </Box>
          <FullscreenToggleButton fullScreen={serverHealthFullScreen} onToggle={() => setServerHealthFullScreen(f => !f)} color="white" />
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {healthLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
              <CircularProgress size={32} />
            </Box>
          ) : (
            <Stack spacing={2.5}>
              {/* Server info */}
              {health && (
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                    {t('database.dbHealth.serverInfo')}
                  </Typography>
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 6 }}>
                      <Typography variant="caption" color="text.secondary">{t('database.dbHealth.pgVersion')}</Typography>
                      <Typography variant="body2" fontWeight={600} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                        {health.pgVersion}
                      </Typography>
                    </Grid>
                    <Grid size={{ xs: 6 }}>
                      <Typography variant="caption" color="text.secondary">{t('database.dbHealth.uptime')}</Typography>
                      <Typography variant="body2" fontWeight={600} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                        {health.serverStartedAt ? formatUptime(health.serverStartedAt) : '-'}
                      </Typography>
                    </Grid>
                  </Grid>
                </Paper>
              )}

              {/* Connection capacity */}
              {health && (
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                    {t('database.dbHealth.connections')}
                  </Typography>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" color="text.secondary">
                      {health.totalConnections} / {health.maxConnections}
                    </Typography>
                    <Typography variant="body2" fontWeight="bold">
                      {health.maxConnections > 0 ? Math.round(health.totalConnections / health.maxConnections * 100) : 0}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={Math.min(100, health.maxConnections > 0 ? health.totalConnections / health.maxConnections * 100 : 0)}
                    sx={{
                      height: 10, borderRadius: 5, bgcolor: 'grey.200',
                      '& .MuiLinearProgress-bar': {
                        borderRadius: 5,
                        bgcolor: health.totalConnections / health.maxConnections > 0.9 ? 'error.main'
                          : health.totalConnections / health.maxConnections > 0.7 ? 'warning.main'
                          : 'success.main',
                      },
                    }}
                  />
                </Paper>
              )}

              {/* Disk usage */}
              {health && (
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                    {t('database.dbHealth.diskUsage')}
                  </Typography>
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 4 }}>
                      <Typography variant="caption" color="text.secondary">{t('database.dbHealth.totalDisk')}</Typography>
                      <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                        {formatBytes(health.totalDiskSize)}
                      </Typography>
                    </Grid>
                    <Grid size={{ xs: 4 }}>
                      <Typography variant="caption" color="text.secondary">{t('database.dbHealth.avgSize')}</Typography>
                      <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                        {formatBytes(healthMetrics.avgSize)}
                      </Typography>
                    </Grid>
                    <Grid size={{ xs: 4 }}>
                      <Typography variant="caption" color="text.secondary">{t('database.dbSummary.wasted')}</Typography>
                      <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: metrics.neverUsedSize > 0 ? 'error.main' : 'text.primary' }}>
                        {formatBytes(metrics.neverUsedSize)}
                      </Typography>
                    </Grid>
                  </Grid>
                </Paper>
              )}

              {/* Database overview */}
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                  {t('database.dbHealth.overview')}
                </Typography>
                <Grid container spacing={2}>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbSummary.databases')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {databases.length}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbSummary.inUse')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'info.main' }}>
                      {metrics.withConnections}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbSummary.protected')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'success.main' }}>
                      {metrics.protectedCount}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbSummary.neverUsed')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'warning.main' }}>
                      {metrics.neverUsedCount}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbHealth.idle30')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: healthMetrics.idle30Count > 0 ? 'error.main' : 'text.primary' }}>
                      {healthMetrics.idle30Count}
                    </Typography>
                  </Grid>
                  <Grid size={{ xs: 4 }}>
                    <Typography variant="caption" color="text.secondary">{t('database.dbHealth.idle30Size')}</Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ fontFamily: "'JetBrains Mono', monospace", color: healthMetrics.idle30Size > 0 ? 'error.main' : 'text.primary' }}>
                      {formatBytes(healthMetrics.idle30Size)}
                    </Typography>
                  </Grid>
                </Grid>
              </Paper>

              {/* Top 5 largest */}
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 1.5, textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em' }}>
                  {t('database.dbHealth.top5')}
                </Typography>
                {healthMetrics.top5.map((db, i) => (
                  <Box key={db.name} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: i < healthMetrics.top5.length - 1 ? 1 : 0 }}>
                    <Typography variant="caption" sx={{ minWidth: 16, fontWeight: 700, color: 'text.secondary' }}>
                      {i + 1}.
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", flex: 1, fontSize: '0.8rem' }}>
                      {db.name}
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, fontSize: '0.8rem' }}>
                      {formatBytes(db.sizeBytes)}
                    </Typography>
                    <LinearProgress
                      variant="determinate"
                      value={Math.min(100, (db.sizeBytes / maxSize) * 100)}
                      sx={{
                        width: 60, height: 6, borderRadius: 3, bgcolor: 'grey.200',
                        '& .MuiLinearProgress-bar': { borderRadius: 3 },
                      }}
                    />
                  </Box>
                ))}
              </Paper>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setHealthOpen(false)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

function formatUptime(startedAt: string): string {
  const start = new Date(startedAt).getTime()
  const now = Date.now()
  const diff = now - start
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
  if (days > 0) return `${days}d ${hours}h ${minutes}m`
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${minutes}m`
}
