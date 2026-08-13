import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { listDumps, deleteDump, deleteDumpsBulk, getStorageInfo, getActiveRestores, updateDumpExpiration, updateDumpSharing, type ActiveRestore } from '../services/dumpService'
import { listSnapshots, deleteSnapshot, deleteSnapshotsBulk, getSnapshotStorageInfo, getActiveSnapshots, updateSnapshotExpiration, updateSnapshotSharing, type ActiveSnapshot } from '../services/snapshotService'
import { isManagedDatabasesEnabled } from '../services/managedDatabaseService'
import { useNotification } from '../components/NotificationProvider'
import { useAuth } from '../components/AuthProvider'
import { P } from '../utils/permissions'
import { useTenantNames } from '../hooks/useTenantNames'
import HeroBanner from '../components/HeroBanner'
import RestoreAttribution from '../components/RestoreAttribution'

const DatabasesTab = lazy(() => import('../components/DatabasesTab'))
import UploadDumpModal from '../components/UploadDumpModal'
import ShareResourceDialog from '../components/ShareResourceDialog'
import RestoreDumpModal from '../components/RestoreDumpModal'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import EditExpirationDialog from '../components/EditExpirationDialog'
import PasswordConfirmDialog from '../components/PasswordConfirmDialog'
import { RateLimitError } from '../services/fetchWithAuth'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useStickyHeader } from '../hooks/useStickyHeader'
import { useDumpMetadataEdit } from '../hooks/useDumpMetadataEdit'
import { useSnapshotMetadataEdit } from '../hooks/useSnapshotMetadataEdit'
import { useExpirationEdit } from '../hooks/useExpirationEdit'
import { useCleanupByIdle } from '../hooks/useCleanupByIdle'
import { useActionMenu } from '../hooks/useActionMenu'
import { useTablePagination } from '../hooks/useTablePagination'
import { formatBytes, formatDate } from '../utils/format'
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
  AlertTitle,
  Tabs,
  Tab,
  Tooltip,
  IconButton,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Slider,
  Switch,
  FormControlLabel,
  Divider,
  Menu,
  MenuItem,
  Stack,
  ListItemIcon,
  ListItemText,
  TablePagination,
} from '@mui/material'
import { Search, Delete, CloudUpload, Download, Restore, Timer, Storage, InsertDriveFile, CameraAlt, InfoOutlined, HelpOutline, CleaningServices, Warning, Edit, Check, Close, Dns, Share } from '@mui/icons-material'

type PendingDelete =
  | { kind: 'dump'; dump: DatabaseDump }
  | { kind: 'dumpBulk'; ids: string[] }
  | { kind: 'snapshot'; snapshot: DatabaseSnapshot }
  | { kind: 'snapshotBulk'; ids: string[] }

export default function DatabasePage() {
  const { notify } = useNotification()
  const { rbacEnabled, hasPermission } = useAuth()
  const canDbOperate = hasPermission(P.DATABASE_OPERATE)
  // deleting dumps/snapshots is destructive and gated separately
  const canDbDelete = hasPermission(P.DATABASE_DELETE)
  const canDbUpload = hasPermission(P.DATABASE_UPLOAD)
  const canViewAudit = hasPermission(P.AUDIT_VIEW)
  const tenantNames = useTenantNames()
  const [shareTarget, setShareTarget] = useState<{ kind: 'dump' | 'snapshot'; id: string; name: string; tenantId: string | null; sharedWith: string[] } | null>(null)
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx, theadCheckboxSx } = useTableHeaderTheme()
  const dumpTableRef = useRef<HTMLDivElement>(null)
  const snapTableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(dumpTableRef)
  useStickyHeader(snapTableRef)
  const [activeTabIndex, setActiveTabIndex] = useState(0)
  const [dbManagedEnabled, setDbManagedEnabled] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)

  type TabId = 'databases' | 'backups' | 'snapshots'
  const tabOrder: TabId[] = useMemo(() => {
    if (dbManagedEnabled) return ['databases', 'backups', 'snapshots']
    return ['backups', 'snapshots']
  }, [dbManagedEnabled])
  const activeTab = tabOrder[activeTabIndex] ?? tabOrder[0]

  // --- Dumps state ---
  const [dumps, setDumps] = useState<DatabaseDump[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [uploadOpen, setUploadOpen] = useState(false)
  const [restoreOpen, setRestoreOpen] = useState(false)
  const [restoreDump, setRestoreDump] = useState<DatabaseDump | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [storageInfo, setStorageInfo] = useState<{ totalBytes: number; fileCount: number; maxBytes: number } | null>(null)
  const [activeRestores, setActiveRestores] = useState<ActiveRestore[]>([])
  const dumpEdit = useDumpMetadataEdit({ notify, setDumps })

  // --- Snapshots state ---
  const [snapshots, setSnapshots] = useState<DatabaseSnapshot[]>([])
  const [snapFilter, setSnapFilter] = useState('')
  const [snapLoading, setSnapLoading] = useState(true)
  const [snapSortKey, setSnapSortKey] = useState<string>('')
  const [snapSortDir, setSnapSortDir] = useState<'asc' | 'desc'>('asc')
  const [snapSelected, setSnapSelected] = useState<Set<string>>(new Set())
  const [snapStorageInfo, setSnapStorageInfo] = useState<{ totalBytes: number; fileCount: number; maxBytes: number } | null>(null)
  const [activeSnaps, setActiveSnaps] = useState<ActiveSnapshot[]>([])
  const [snapshotOpen, setSnapshotOpen] = useState(false)
  const [restoreSnapOpen, setRestoreSnapOpen] = useState(false)
  const [restoreSnapshot, setRestoreSnapshot] = useState<DatabaseSnapshot | null>(null)
  const snapEdit = useSnapshotMetadataEdit({ notify, setSnapshots })

  // --- Delete dialog state (unified) ---
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)

  const expEdit = useExpirationEdit()

  // --- Filter state ---
  const [showNeverUsedDumps, setShowNeverUsedDumps] = useState(false)
  const [showNeverUsedSnaps, setShowNeverUsedSnaps] = useState(false)

  const dumpMenu = useActionMenu<DatabaseDump>()
  const snapMenu = useActionMenu<DatabaseSnapshot>()

  const DUMP_COLUMNS: { key: string; label: string }[] = useMemo(() => [
    { key: 'originalFilename', label: t('database.dumpColumns.originalFilename') },
    { key: 'version', label: t('database.dumpColumns.version') },
    { key: 'databaseName', label: t('database.dumpColumns.database') },
    { key: 'format', label: t('database.dumpColumns.format') },
    { key: 'fileSize', label: t('database.dumpColumns.size') },
    { key: 'md5Hash', label: t('database.dumpColumns.md5') },
    { key: 'uploadedAt', label: t('database.dumpColumns.uploadedAt') },
    { key: 'expiresAt', label: t('database.dumpColumns.expires') },
    { key: 'lastUsedAt', label: t('database.dumpColumns.lastUsed') },
    ...(canViewAudit ? [{ key: 'createdBy', label: t('database.dumpColumns.createdBy') }] : []),
    ...(rbacEnabled ? [{ key: 'tenantId', label: t('tenants.tenant') }] : []),
    { key: 'action', label: t('database.dumpColumns.actions') },
  ], [t, canViewAudit, rbacEnabled])

  const SNAP_COLUMNS: { key: string; label: string }[] = useMemo(() => [
    { key: 'label', label: t('database.snapColumns.label') },
    { key: 'repository', label: t('database.snapColumns.repository') },
    { key: 'sourceDatabaseName', label: t('database.snapColumns.database') },
    { key: 'containerName', label: t('database.snapColumns.container') },
    { key: 'format', label: t('database.snapColumns.format') },
    { key: 'fileSize', label: t('database.snapColumns.size') },
    { key: 'md5Hash', label: t('database.snapColumns.md5') },
    { key: 'createdAt', label: t('database.snapColumns.createdAt') },
    { key: 'expiresAt', label: t('database.snapColumns.expires') },
    { key: 'lastUsedAt', label: t('database.snapColumns.lastUsed') },
    ...(canViewAudit ? [{ key: 'createdBy', label: t('database.snapColumns.createdBy') }] : []),
    ...(rbacEnabled ? [{ key: 'tenantId', label: t('tenants.tenant') }] : []),
    { key: 'action', label: t('database.snapColumns.actions') },
  ], [t, canViewAudit, rbacEnabled])

  // --- Dumps logic ---
  function handleSort(key: string) {
    if (key === 'action') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  const loadDumps = useCallback(() => {
    setLoading(true)
    setSelected(new Set())
    listDumps()
      .then(setDumps)
      .catch(() => setDumps([]))
      .finally(() => setLoading(false))
    getStorageInfo()
      .then(setStorageInfo)
      .catch(() => setStorageInfo(null))
  }, [])

  const loadSnapshots = useCallback(() => {
    setSnapLoading(true)
    setSnapSelected(new Set())
    listSnapshots()
      .then(setSnapshots)
      .catch(() => setSnapshots([]))
      .finally(() => setSnapLoading(false))
    getSnapshotStorageInfo()
      .then(setSnapStorageInfo)
      .catch(() => setSnapStorageInfo(null))
  }, [])

  const cleanup = useCleanupByIdle({ notify, t, loadDumps, loadSnapshots })

  const cleanupCandidates = useMemo(() => {
    if (cleanup.target === null) return []
    const cutoff = Date.now() - cleanup.minDays * 24 * 60 * 60 * 1000
    if (cleanup.target === 'dump') {
      return dumps.filter(d => {
        if (!d.lastUsedAt) return true
        return new Date(d.lastUsedAt).getTime() < cutoff
      })
    }
    return snapshots.filter(s => {
      if (!s.lastUsedAt) return true
      return new Date(s.lastUsedAt).getTime() < cutoff
    })
  }, [dumps, snapshots, cleanup.target, cleanup.minDays])

  useEffect(() => {
    loadDumps()
    loadSnapshots()
    isManagedDatabasesEnabled().then(setDbManagedEnabled).catch(() => setDbManagedEnabled(false))
  }, [loadDumps, loadSnapshots])

  useEffect(() => {
    const check = () => {
      getActiveRestores().then(setActiveRestores).catch(() => setActiveRestores([]))
      getActiveSnapshots().then(setActiveSnaps).catch(() => setActiveSnaps([]))
    }
    check()
    const interval = setInterval(check, 3000)
    return () => clearInterval(interval)
  }, [])

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleSelectAll() {
    if (selected.size === filteredDumps.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(filteredDumps.map((d) => d.id)))
    }
  }

  function handleBulkDeleteClick() {
    setPendingDelete({ kind: 'dumpBulk', ids: [...selected] })
  }

  function handleRestoreClick(dump: DatabaseDump) {
    setRestoreDump(dump)
    setRestoreOpen(true)
  }

  function handleDeleteClick(dump: DatabaseDump) {
    setPendingDelete({ kind: 'dump', dump })
  }

  const filteredDumps = useMemo(() => {
    let data = showNeverUsedDumps ? dumps.filter(d => !d.lastUsedAt) : dumps
    const result = data.filter((d) =>
      [d.originalFilename, d.databaseName ?? '', d.version ?? '', d.format, formatBytes(d.fileSize), d.description ?? '', d.createdBy ?? '',
       d.tenantId ? tenantNames.get(d.tenantId) ?? '' : '']
        .some((v) => v.toLowerCase().includes(filter.toLowerCase())),
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      if (sortKey === 'fileSize') {
        const cmp = a.fileSize - b.fileSize
        return sortDir === 'asc' ? cmp : -cmp
      }
      // the tenant column displays the resolved name, so sort by it too
      const sortValue = (d: DatabaseDump) => sortKey === 'tenantId'
        ? (d.tenantId ? tenantNames.get(d.tenantId) ?? d.tenantId : '')
        : String((d as unknown as Record<string, unknown>)[sortKey] ?? '')
      const cmp = sortValue(a).toLowerCase().localeCompare(sortValue(b).toLowerCase())
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [dumps, filter, sortKey, sortDir, showNeverUsedDumps, tenantNames])

  const dumpPagination = useTablePagination(filteredDumps, { storageKey: 'dumps' })

  // --- Snapshots logic ---
  function handleSnapSort(key: string) {
    if (key === 'action') return
    setSnapSortDir(snapSortKey === key && snapSortDir === 'asc' ? 'desc' : 'asc')
    setSnapSortKey(key)
  }

  function toggleSnapSelect(id: string) {
    setSnapSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleSnapSelectAll() {
    if (snapSelected.size === filteredSnapshots.length) {
      setSnapSelected(new Set())
    } else {
      setSnapSelected(new Set(filteredSnapshots.map((s) => s.id)))
    }
  }

  function handleSnapBulkDeleteClick() {
    setPendingDelete({ kind: 'snapshotBulk', ids: [...snapSelected] })
  }

  function handleSnapDeleteClick(snap: DatabaseSnapshot) {
    setPendingDelete({ kind: 'snapshot', snapshot: snap })
  }

  async function handleDeleteConfirm(password: string) {
    if (!pendingDelete) return
    try {
      if (pendingDelete.kind === 'dump') {
        const result = await deleteDump(pendingDelete.dump.id, password)
        if (result.success) {
          notify(t('database.dumpDeleted'), 'success')
          setPendingDelete(null)
          loadDumps()
        } else {
          notify(result.error || t('database.deleteFailed'), 'error')
        }
      } else if (pendingDelete.kind === 'dumpBulk') {
        const result = await deleteDumpsBulk(pendingDelete.ids, password)
        if (result.success) {
          notify(t('database.dumpsDeleted', { count: result.deleted }), 'success')
          setSelected(new Set())
          setPendingDelete(null)
          loadDumps()
        } else {
          notify(result.error || t('database.deleteFailed'), 'error')
        }
      } else if (pendingDelete.kind === 'snapshot') {
        const result = await deleteSnapshot(pendingDelete.snapshot.id, password)
        if (result.success) {
          notify(t('database.snapshotDeleted'), 'success')
          setPendingDelete(null)
          loadSnapshots()
        } else {
          notify(result.error || t('database.deleteFailed'), 'error')
        }
      } else if (pendingDelete.kind === 'snapshotBulk') {
        const result = await deleteSnapshotsBulk(pendingDelete.ids, password)
        if (result.success) {
          notify(t('database.snapshotsDeleted', { count: result.deleted }), 'success')
          setSnapSelected(new Set())
          setPendingDelete(null)
          loadSnapshots()
        } else {
          notify(result.error || t('database.deleteFailed'), 'error')
        }
      }
    } catch (e) {
      if (e instanceof RateLimitError) throw e
      notify(t('common.unexpectedError'), 'error')
    }
  }

  function getDeleteDialogTitle(): string {
    if (!pendingDelete) return ''
    switch (pendingDelete.kind) {
      case 'dump': return t('database.deleteDump')
      case 'dumpBulk': return t('database.deleteDumps', { count: pendingDelete.ids.length })
      case 'snapshot': return t('database.deleteSnapshot')
      case 'snapshotBulk': return t('database.deleteSnapshots', { count: pendingDelete.ids.length })
    }
  }

  function getDeleteDialogMessage(): string {
    if (!pendingDelete) return ''
    switch (pendingDelete.kind) {
      case 'dump': return t('database.deleteDumpConfirm', { filename: pendingDelete.dump.originalFilename })
      case 'dumpBulk': return t('database.deleteDumpsBulkConfirm', { count: pendingDelete.ids.length })
      case 'snapshot': return t('database.deleteSnapshotConfirm', { name: pendingDelete.snapshot.label || pendingDelete.snapshot.sourceDatabaseName })
      case 'snapshotBulk': return t('database.deleteSnapshotsBulkConfirm', { count: pendingDelete.ids.length })
    }
  }

  function handleEditDumpExpiration(dump: DatabaseDump) {
    expEdit.openEdit(dump.originalFilename, dump.expiresAt ?? null, async (expiresAt, password) => {
      const result = await updateDumpExpiration(dump.id, expiresAt, password)
      if (result.success) loadDumps()
      return result
    })
  }

  function handleEditSnapExpiration(snap: DatabaseSnapshot) {
    expEdit.openEdit(snap.label || snap.sourceDatabaseName, snap.expiresAt ?? null, async (expiresAt, password) => {
      const result = await updateSnapshotExpiration(snap.id, expiresAt, password)
      if (result.success) loadSnapshots()
      return result
    })
  }

  const filteredSnapshots = useMemo(() => {
    let data = showNeverUsedSnaps ? snapshots.filter(s => !s.lastUsedAt) : snapshots
    const result = data.filter((s) =>
      [s.label ?? '', s.repository, s.sourceDatabaseName, s.containerName ?? '', s.format, formatBytes(s.fileSize), s.description ?? '', s.createdBy ?? '',
       s.tenantId ? tenantNames.get(s.tenantId) ?? '' : '']
        .some((v) => v.toLowerCase().includes(snapFilter.toLowerCase())),
    )
    if (!snapSortKey) return result
    return [...result].sort((a, b) => {
      if (snapSortKey === 'fileSize') {
        const cmp = a.fileSize - b.fileSize
        return snapSortDir === 'asc' ? cmp : -cmp
      }
      // the tenant column displays the resolved name, so sort by it too
      const sortValue = (snap: DatabaseSnapshot) => snapSortKey === 'tenantId'
        ? (snap.tenantId ? tenantNames.get(snap.tenantId) ?? snap.tenantId : '')
        : String((snap as unknown as Record<string, unknown>)[snapSortKey] ?? '')
      const cmp = sortValue(a).toLowerCase().localeCompare(sortValue(b).toLowerCase())
      return snapSortDir === 'asc' ? cmp : -cmp
    })
  }, [snapshots, snapFilter, snapSortKey, snapSortDir, showNeverUsedSnaps, tenantNames])

  const snapPagination = useTablePagination(filteredSnapshots, { storageKey: 'snapshots' })

  const currentStorageInfo = activeTab === 'backups' ? storageInfo : snapStorageInfo
  const currentFileLabel = activeTab === 'backups'
    ? (currentStorageInfo?.fileCount === 1 ? t('database.dumpFile') : t('database.dumpFiles'))
    : (currentStorageInfo?.fileCount === 1 ? t('database.snapshotFile') : t('database.snapshotFiles'))

  return (
    <>
      <HeroBanner linkTo="/" linkLabel={t('hero.exploreContainers')} />

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 1 }}>
          {t('database.title')}
        </Typography>

        <Tabs value={activeTabIndex} onChange={(_e, v) => setActiveTabIndex(v)} sx={{ mb: 1 }}>
          {tabOrder.map((tabId) => {
            switch (tabId) {
              case 'databases':
                return <Tab key="databases" icon={<Dns />} iconPosition="start" label={t('database.databasesTab')} />
              case 'backups':
                return <Tab key="backups" icon={<CloudUpload />} iconPosition="start" label={t('database.dumpsTab', { count: dumps.length })} />
              case 'snapshots':
                return <Tab key="snapshots" icon={<CameraAlt />} iconPosition="start" label={t('database.snapshotsTab', { count: snapshots.length })} />
            }
          })}
        </Tabs>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 2 }}>
          <Typography variant="body2" color="text.secondary">
            {activeTab === 'databases' && t('database.databasesTabDesc')}
            {activeTab === 'backups' && t('database.dumpsTabDesc')}
            {activeTab === 'snapshots' && t('database.snapshotsTabDesc')}
          </Typography>
          <IconButton size="small" onClick={() => setHelpOpen(true)} sx={{ color: 'text.disabled' }}>
            <HelpOutline sx={{ fontSize: 18 }} />
          </IconButton>
        </Box>

        <Dialog open={helpOpen} onClose={() => setHelpOpen(false)} maxWidth="sm" fullWidth>
          <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <HelpOutline color="primary" />
            {activeTab === 'databases' && t('database.databasesTab')}
            {activeTab === 'backups' && t('database.dumpsTab', { count: dumps.length })}
            {activeTab === 'snapshots' && t('database.snapshotsTab', { count: snapshots.length })}
            <IconButton onClick={() => setHelpOpen(false)} sx={{ ml: 'auto' }}>
              <Close />
            </IconButton>
          </DialogTitle>
          <DialogContent dividers>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-line', '& strong': { color: 'warning.main' } }}>
              <span dangerouslySetInnerHTML={{ __html:
                activeTab === 'databases' ? t('database.databasesTabHelp')
                : activeTab === 'backups' ? t('database.dumpsTabHelp')
                : t('database.snapshotsTabHelp')
              }} />
            </Typography>
          </DialogContent>
        </Dialog>

        {(activeTab === 'backups' || activeTab === 'snapshots') && currentStorageInfo && currentStorageInfo.maxBytes > 0 && (
          <Paper elevation={2} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
            <Grid container spacing={3} alignItems="center">
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Storage color="primary" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                      {formatBytes(currentStorageInfo.totalBytes)}
                    </Typography>
                    <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>
                      {t('database.storageUsed', { max: formatBytes(currentStorageInfo.maxBytes) })}
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <InsertDriveFile color="action" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>
                      {currentStorageInfo.fileCount}
                    </Typography>
                    <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>
                      {currentFileLabel}
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" color="text.secondary">{t('database.storageUsage')}</Typography>
                    <Typography variant="body2" fontWeight="bold">
                      {currentStorageInfo.maxBytes > 0 ? Math.min(100, (currentStorageInfo.totalBytes / currentStorageInfo.maxBytes * 100)).toFixed(1) : 0}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={Math.min(100, currentStorageInfo.totalBytes / currentStorageInfo.maxBytes * 100)}
                    sx={{
                      height: 10, borderRadius: 5, bgcolor: 'grey.200',
                      '& .MuiLinearProgress-bar': {
                        borderRadius: 5,
                        bgcolor: currentStorageInfo.totalBytes / currentStorageInfo.maxBytes > 0.9 ? 'error.main'
                          : currentStorageInfo.totalBytes / currentStorageInfo.maxBytes > 0.7 ? 'warning.main'
                          : 'success.main',
                      },
                    }}
                  />
                </Box>
              </Grid>
            </Grid>
          </Paper>
        )}

        {(activeTab === 'backups' || activeTab === 'snapshots') && activeRestores.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>{t('database.restoreInProgress')}</AlertTitle>
            {activeRestores.map((r, i) => (
              <Stack key={i} direction="row" alignItems="center" spacing={1} sx={{ mb: 0.5 }}>
                <LinearProgress sx={{ width: 80 }} />
                <Box>
                  <Typography variant="body2">
                    <span dangerouslySetInnerHTML={{ __html: t('database.restoringInto', { filename: r.dumpFilename, database: r.targetDatabase, repository: r.repository }) }} />
                  </Typography>
                  <RestoreAttribution restore={r} tenantNames={tenantNames} />
                </Box>
              </Stack>
            ))}
          </Alert>
        )}

        {activeTab === 'snapshots' && activeSnaps.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>{t('database.snapshotInProgress')}</AlertTitle>
            {activeSnaps.map((s, i) => (
              <Stack key={i} direction="row" alignItems="center" spacing={1}>
                <LinearProgress sx={{ width: 80 }} />
                <Typography variant="body2">
                  <span dangerouslySetInnerHTML={{ __html: t('database.creatingSnapshotOf', { database: s.sourceDatabaseName, repository: s.repository }) }} />
                </Typography>
              </Stack>
            ))}
          </Alert>
        )}

        {/* ==================== DUMPS TAB ==================== */}
        {activeTab === 'backups' && (
          <>
            <Box sx={{ display: 'flex', gap: 2, mb: 3, alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField
                placeholder={t('database.searchDumps')}
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                size="small"
                sx={{ flex: 1, minWidth: 200 }}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search color="action" /></InputAdornment> } }}
              />
              <FormControlLabel
                control={<Switch checked={showNeverUsedDumps} onChange={(e) => setShowNeverUsedDumps(e.target.checked)} size="small" />}
                label={<Typography variant="body2">{t('database.showNeverUsed')}</Typography>}
              />
              {canDbDelete && selected.size > 0 && (
                <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleBulkDeleteClick} size="small">
                  {t('common.delete')} ({selected.size})
                </Button>
              )}
              {canDbDelete && (
                <Tooltip title={t('database.cleanUpByIdleDesc')}>
                  <Button variant="contained" color="warning" startIcon={<CleaningServices />} onClick={() => cleanup.open('dump')} disabled={dumps.length === 0} size="small">
                    {t('database.cleanUpByIdle')}
                  </Button>
                </Tooltip>
              )}
              {canDbUpload && (
                <Button variant="contained" color="primary" startIcon={<CloudUpload />} onClick={() => setUploadOpen(true)} size="small">
                  {t('database.uploadDump')}
                </Button>
              )}
            </Box>
            <Paper elevation={2} sx={{ borderRadius: 2 }}>
              <TableContainer ref={dumpTableRef}>
                <Table stickyHeader aria-label="Database dumps">
                <TableHead>
                  <TableRow>
                    <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                      <Checkbox
                        checked={filteredDumps.length > 0 && selected.size === filteredDumps.length}
                        indeterminate={selected.size > 0 && selected.size < filteredDumps.length}
                        onChange={toggleSelectAll}
                        sx={theadCheckboxSx}
                      />
                    </TableCell>
                    {DUMP_COLUMNS.map((col) => (
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
                      <TableCell colSpan={DUMP_COLUMNS.length + 1} align="center" sx={{ py: 4 }}>
                        <CircularProgress size={28} />
                      </TableCell>
                    </TableRow>
                  )}
                  {!loading && filteredDumps.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={DUMP_COLUMNS.length + 1} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                        {t('database.noDumpsFound')}
                      </TableCell>
                    </TableRow>
                  )}
                  {dumpPagination.paginatedData.map((dump) => (
                    <Tooltip
                      key={dump.id}
                      title={dump.description ? (
                        <Box sx={{ p: 0.5 }}>
                          <Typography variant="caption" fontWeight={700} sx={{ display: 'block', mb: 0.5, opacity: 0.8 }}>
                            {t('common.description')}
                          </Typography>
                          <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                            {dump.description}
                          </Typography>
                        </Box>
                      ) : ''}
                      placement="bottom-start"
                      arrow
                      disableHoverListener={!dump.description}
                      slotProps={{
                        tooltip: {
                          sx: {
                            bgcolor: 'primary.dark',
                            maxWidth: 360,
                            borderRadius: 2,
                            px: 2, py: 1.5,
                            boxShadow: 3,
                            '& .MuiTooltip-arrow': { color: 'primary.dark', left: '50% !important', transform: 'translateX(-50%) !important' },
                          },
                        },
                      }}
                    >
                    <TableRow
                      hover
                      sx={{ cursor: 'pointer' }}
                      selected={selected.has(dump.id)}
                      onContextMenu={(e) => {
                        e.preventDefault()
                        dumpMenu.openByPosition({ top: e.clientY, left: e.clientX }, dump)
                      }}
                    >
                      <TableCell padding="checkbox">
                        <Checkbox checked={selected.has(dump.id)} onChange={() => toggleSelect(dump.id)} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                          {dump.originalFilename}
                          {dump.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                        </Box>
                        {dumpEdit.editingId === dump.id && (
                          <TextField
                            value={dumpEdit.description}
                            onChange={(e) => dumpEdit.setDescription(e.target.value)}
                            size="small"
                            variant="standard"
                            placeholder={t('common.description')}
                            multiline
                            maxRows={3}
                            fullWidth
                            sx={{ mt: 0.5 }}
                            onKeyDown={(e) => { if (e.key === 'Escape') dumpEdit.cancelEdit() }}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        {dumpEdit.editingId === dump.id ? (
                          <TextField
                            value={dumpEdit.version}
                            onChange={(e) => dumpEdit.setVersion(e.target.value)}
                            size="small"
                            variant="standard"
                            placeholder="-"
                            autoFocus
                            sx={{ width: 100 }}
                            onKeyDown={(e) => { if (e.key === 'Enter') dumpEdit.saveEdit(); if (e.key === 'Escape') dumpEdit.cancelEdit() }}
                          />
                        ) : (
                          <Box
                            onClick={canDbOperate ? () => dumpEdit.startEdit(dump) : undefined}
                            sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.5, '&:hover .edit-icon': { opacity: 1 } }}
                          >
                            {dump.version || '-'}
                            <Edit className="edit-icon" sx={{ fontSize: 14, opacity: 0, color: 'text.secondary', transition: 'opacity 0.2s' }} />
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>
                        {dumpEdit.editingId === dump.id ? (
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <TextField
                              value={dumpEdit.database}
                              onChange={(e) => dumpEdit.setDatabase(e.target.value)}
                              size="small"
                              variant="standard"
                              placeholder="-"
                              sx={{ width: 100 }}
                              onKeyDown={(e) => { if (e.key === 'Escape') dumpEdit.cancelEdit() }}
                            />
                            <IconButton size="small" onClick={() => dumpEdit.saveEdit()} disabled={dumpEdit.saving} color="success">
                              {dumpEdit.saving ? <CircularProgress size={14} /> : <Check sx={{ fontSize: 16 }} />}
                            </IconButton>
                            <IconButton size="small" onClick={() => dumpEdit.cancelEdit()} disabled={dumpEdit.saving}>
                              <Close sx={{ fontSize: 16 }} />
                            </IconButton>
                          </Box>
                        ) : (
                          <Box
                            onClick={canDbOperate ? () => dumpEdit.startEdit(dump) : undefined}
                            sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.5, '&:hover .edit-icon': { opacity: 1 } }}
                          >
                            {dump.databaseName || '-'}
                            <Edit className="edit-icon" sx={{ fontSize: 14, opacity: 0, color: 'text.secondary', transition: 'opacity 0.2s' }} />
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>
                        <Chip label={dump.format} size="small" color={dump.format === 'SQL' ? 'primary' : dump.format === 'CUSTOM' ? 'secondary' : 'default'} variant="outlined" />
                      </TableCell>
                      <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{formatBytes(dump.fileSize)}</TableCell>
                      <TableCell>
                        <Typography variant="caption" fontFamily="monospace" title={dump.md5Hash} sx={{ cursor: 'default' }}>
                          {dump.md5Hash ? dump.md5Hash.substring(0, 8) + '...' : '-'}
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(dump.uploadedAt)}</TableCell>
                      <TableCell>
                        <Chip
                          icon={<Timer />}
                          label={dump.expiresAt ? formatDate(dump.expiresAt) : t('common.noExpiration')}
                          size="small"
                          color={dump.expiresAt && new Date(dump.expiresAt) < new Date() ? 'error' : 'default'}
                          variant="outlined"
                          onClick={canDbOperate ? () => handleEditDumpExpiration(dump) : undefined}
                          sx={canDbOperate ? { cursor: 'pointer' } : undefined}
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={getLastUsedLabel(dump.lastUsedAt, false, '', t('database.neverUsed'))}
                          color={getLastUsedColor(dump.lastUsedAt)}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>
                      {canViewAudit && (
                        <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                          {dump.createdBy || '-'}
                        </TableCell>
                      )}
                      {rbacEnabled && (
                        <TableCell>
                          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                            {dump.tenantId
                              ? <Chip label={tenantNames.get(dump.tenantId) ?? dump.tenantId} size="small" variant="outlined" color="secondary" />
                              : '-'}
                            {(dump.sharedWithTenants?.length ?? 0) > 0 && (
                              <Tooltip title={`${t('tenants.sharedWith')}: ${(dump.sharedWithTenants ?? []).map(id => tenantNames.get(id) ?? id).join(', ')}`}>
                                <Chip icon={<Share sx={{ fontSize: 14 }} />} label={dump.sharedWithTenants!.length} size="small" variant="outlined" color="info" />
                              </Tooltip>
                            )}
                          </Box>
                        </TableCell>
                      )}
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 0.25 }}>
                          <Tooltip title={t('common.download')}>
                            <IconButton size="small" color="primary" component="a" href={`/api/database/dumps/download/${dump.id}`}>
                              <Download />
                            </IconButton>
                          </Tooltip>
                          {canDbOperate && rbacEnabled && (
                            <Tooltip title={t('tenants.sharing.title')}>
                              <IconButton size="small" color="info" onClick={() => setShareTarget({ kind: 'dump', id: dump.id, name: dump.originalFilename, tenantId: dump.tenantId ?? null, sharedWith: dump.sharedWithTenants ?? [] })}>
                                <Share />
                              </IconButton>
                            </Tooltip>
                          )}
                          {canDbOperate && (
                            <Tooltip title={t('common.restore')}>
                              <IconButton size="small" color="success" onClick={() => handleRestoreClick(dump)}>
                                <Restore />
                              </IconButton>
                            </Tooltip>
                          )}
                          {canDbDelete && (
                            <Tooltip title={t('common.delete')}>
                              <IconButton size="small" color="error" onClick={() => handleDeleteClick(dump)}>
                                <Delete />
                              </IconButton>
                            </Tooltip>
                          )}
                        </Box>
                      </TableCell>
                    </TableRow>
                    </Tooltip>
                  ))}
                </TableBody>
              </Table>
              </TableContainer>
            </Paper>
            <TablePagination
              component="div"
              count={dumpPagination.totalCount}
              page={dumpPagination.page}
              onPageChange={dumpPagination.handleChangePage}
              rowsPerPage={dumpPagination.rowsPerPage}
              onRowsPerPageChange={dumpPagination.handleChangeRowsPerPage}
              rowsPerPageOptions={[10, 25, 50, 100]}
              labelRowsPerPage={t('common.rowsPerPage')}
            />
          </>
        )}

        {/* ==================== SNAPSHOTS TAB ==================== */}
        {activeTab === 'snapshots' && (
          <>
            <Box sx={{ display: 'flex', gap: 2, mb: 3, alignItems: 'center', flexWrap: 'wrap' }}>
              <TextField
                placeholder={t('database.searchSnapshots')}
                value={snapFilter}
                onChange={(e) => setSnapFilter(e.target.value)}
                size="small"
                sx={{ flex: 1, minWidth: 200 }}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search color="action" /></InputAdornment> } }}
              />
              <FormControlLabel
                control={<Switch checked={showNeverUsedSnaps} onChange={(e) => setShowNeverUsedSnaps(e.target.checked)} size="small" />}
                label={<Typography variant="body2">{t('database.showNeverUsed')}</Typography>}
              />
              {canDbDelete && snapSelected.size > 0 && (
                <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleSnapBulkDeleteClick} size="small">
                  {t('common.delete')} ({snapSelected.size})
                </Button>
              )}
              {canDbDelete && (
                <Tooltip title={t('database.cleanUpByIdleDesc')}>
                  <Button variant="contained" color="warning" startIcon={<CleaningServices />} onClick={() => cleanup.open('snapshot')} disabled={snapshots.length === 0} size="small">
                    {t('database.cleanUpByIdle')}
                  </Button>
                </Tooltip>
              )}
              {canDbOperate && (
                <Button variant="contained" color="primary" startIcon={<CameraAlt />} onClick={() => setSnapshotOpen(true)} size="small">
                  {t('database.createSnapshot')}
                </Button>
              )}
            </Box>
            <Paper elevation={2} sx={{ borderRadius: 2 }}>
              <TableContainer ref={snapTableRef}>
                <Table stickyHeader aria-label="Database snapshots">
                <TableHead>
                  <TableRow>
                    <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                      <Checkbox
                        checked={filteredSnapshots.length > 0 && snapSelected.size === filteredSnapshots.length}
                        indeterminate={snapSelected.size > 0 && snapSelected.size < filteredSnapshots.length}
                        onChange={toggleSnapSelectAll}
                        sx={theadCheckboxSx}
                      />
                    </TableCell>
                    {SNAP_COLUMNS.map((col) => (
                      <TableCell key={col.key} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>
                        {col.key !== 'action' ? (
                          <TableSortLabel
                            active={snapSortKey === col.key}
                            direction={snapSortKey === col.key ? snapSortDir : 'asc'}
                            onClick={() => handleSnapSort(col.key)}
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
                  {snapLoading && (
                    <TableRow>
                      <TableCell colSpan={SNAP_COLUMNS.length + 1} align="center" sx={{ py: 4 }}>
                        <CircularProgress size={28} />
                      </TableCell>
                    </TableRow>
                  )}
                  {!snapLoading && filteredSnapshots.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={SNAP_COLUMNS.length + 1} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                        {t('database.noSnapshotsFound')}
                      </TableCell>
                    </TableRow>
                  )}
                  {snapPagination.paginatedData.map((snap) => (
                    <Tooltip
                      key={snap.id}
                      title={snap.description ? (
                        <Box sx={{ p: 0.5 }}>
                          <Typography variant="caption" fontWeight={700} sx={{ display: 'block', mb: 0.5, opacity: 0.8 }}>
                            {t('common.description')}
                          </Typography>
                          <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                            {snap.description}
                          </Typography>
                        </Box>
                      ) : ''}
                      placement="bottom-start"
                      arrow
                      disableHoverListener={!snap.description}
                      slotProps={{
                        tooltip: {
                          sx: {
                            bgcolor: 'primary.dark',
                            maxWidth: 360,
                            borderRadius: 2,
                            px: 2, py: 1.5,
                            boxShadow: 3,
                            '& .MuiTooltip-arrow': { color: 'primary.dark', left: '50% !important', transform: 'translateX(-50%) !important' },
                          },
                        },
                      }}
                    >
                    <TableRow
                      hover
                      sx={{ cursor: 'pointer' }}
                      selected={snapSelected.has(snap.id)}
                      onContextMenu={(e) => {
                        e.preventDefault()
                        snapMenu.openByPosition({ top: e.clientY, left: e.clientX }, snap)
                      }}
                    >
                      <TableCell padding="checkbox">
                        <Checkbox checked={snapSelected.has(snap.id)} onChange={() => toggleSnapSelect(snap.id)} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                        {snapEdit.editingId === snap.id ? (
                          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <TextField
                                value={snapEdit.label}
                                onChange={(e) => snapEdit.setLabel(e.target.value)}
                                size="small"
                                variant="standard"
                                placeholder="-"
                                autoFocus
                                sx={{ width: 140 }}
                                onKeyDown={(e) => { if (e.key === 'Escape') snapEdit.cancelEdit() }}
                              />
                              <IconButton size="small" onClick={() => snapEdit.saveEdit()} disabled={snapEdit.saving} color="success">
                                {snapEdit.saving ? <CircularProgress size={14} /> : <Check sx={{ fontSize: 16 }} />}
                              </IconButton>
                              <IconButton size="small" onClick={() => snapEdit.cancelEdit()} disabled={snapEdit.saving}>
                                <Close sx={{ fontSize: 16 }} />
                              </IconButton>
                            </Box>
                            <TextField
                              value={snapEdit.description}
                              onChange={(e) => snapEdit.setDescription(e.target.value)}
                              size="small"
                              variant="standard"
                              placeholder={t('common.description')}
                              multiline
                              maxRows={3}
                              fullWidth
                              onKeyDown={(e) => { if (e.key === 'Escape') snapEdit.cancelEdit() }}
                            />
                          </Box>
                        ) : (
                          <Box
                            onClick={canDbOperate ? () => snapEdit.startEdit(snap) : undefined}
                            sx={{ display: 'flex', alignItems: 'center', gap: 0.5, cursor: 'pointer', '&:hover .edit-icon': { opacity: 1 } }}
                          >
                            {snap.label || '-'}
                            {snap.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                            <Edit className="edit-icon" sx={{ fontSize: 14, opacity: 0, color: 'text.secondary', transition: 'opacity 0.2s' }} />
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>{snap.repository}</TableCell>
                      <TableCell>{snap.sourceDatabaseName}</TableCell>
                      <TableCell>{snap.containerName || '-'}</TableCell>
                      <TableCell>
                        <Chip label={snap.format} size="small" color={snap.format === 'SQL' ? 'primary' : 'secondary'} variant="outlined" />
                      </TableCell>
                      <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{formatBytes(snap.fileSize)}</TableCell>
                      <TableCell>
                        <Typography variant="caption" fontFamily="monospace" title={snap.md5Hash} sx={{ cursor: 'default' }}>
                          {snap.md5Hash ? snap.md5Hash.substring(0, 8) + '...' : '-'}
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(snap.createdAt)}</TableCell>
                      <TableCell>
                        <Chip
                          icon={<Timer />}
                          label={snap.expiresAt ? formatDate(snap.expiresAt) : t('common.noExpiration')}
                          size="small"
                          color={snap.expiresAt && new Date(snap.expiresAt) < new Date() ? 'error' : 'default'}
                          variant="outlined"
                          onClick={canDbOperate ? () => handleEditSnapExpiration(snap) : undefined}
                          sx={canDbOperate ? { cursor: 'pointer' } : undefined}
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={getLastUsedLabel(snap.lastUsedAt, false, '', t('database.neverUsed'))}
                          color={getLastUsedColor(snap.lastUsedAt)}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>
                      {canViewAudit && (
                        <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                          {snap.createdBy || '-'}
                        </TableCell>
                      )}
                      {rbacEnabled && (
                        <TableCell>
                          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                            {snap.tenantId
                              ? <Chip label={tenantNames.get(snap.tenantId) ?? snap.tenantId} size="small" variant="outlined" color="secondary" />
                              : '-'}
                            {(snap.sharedWithTenants?.length ?? 0) > 0 && (
                              <Tooltip title={`${t('tenants.sharedWith')}: ${(snap.sharedWithTenants ?? []).map(id => tenantNames.get(id) ?? id).join(', ')}`}>
                                <Chip icon={<Share sx={{ fontSize: 14 }} />} label={snap.sharedWithTenants!.length} size="small" variant="outlined" color="info" />
                              </Tooltip>
                            )}
                          </Box>
                        </TableCell>
                      )}
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 0.25 }}>
                          <Tooltip title={t('common.download')}>
                            <IconButton size="small" color="primary" component="a" href={`/api/database/snapshots/download/${snap.id}`}>
                              <Download />
                            </IconButton>
                          </Tooltip>
                          {canDbOperate && rbacEnabled && (
                            <Tooltip title={t('tenants.sharing.title')}>
                              <IconButton size="small" color="info" onClick={() => setShareTarget({ kind: 'snapshot', id: snap.id, name: snap.label || snap.sourceDatabaseName, tenantId: snap.tenantId ?? null, sharedWith: snap.sharedWithTenants ?? [] })}>
                                <Share />
                              </IconButton>
                            </Tooltip>
                          )}
                          {canDbOperate && (
                            <Tooltip title={t('common.restore')}>
                              <IconButton size="small" color="success" onClick={() => { setRestoreSnapshot(snap); setRestoreSnapOpen(true) }}>
                                <Restore />
                              </IconButton>
                            </Tooltip>
                          )}
                          {canDbDelete && (
                            <Tooltip title={t('common.delete')}>
                              <IconButton size="small" color="error" onClick={() => handleSnapDeleteClick(snap)}>
                                <Delete />
                              </IconButton>
                            </Tooltip>
                          )}
                        </Box>
                      </TableCell>
                    </TableRow>
                    </Tooltip>
                  ))}
                </TableBody>
              </Table>
              </TableContainer>
            </Paper>
            <TablePagination
              component="div"
              count={snapPagination.totalCount}
              page={snapPagination.page}
              onPageChange={snapPagination.handleChangePage}
              rowsPerPage={snapPagination.rowsPerPage}
              onRowsPerPageChange={snapPagination.handleChangeRowsPerPage}
              rowsPerPageOptions={[10, 25, 50, 100]}
              labelRowsPerPage={t('common.rowsPerPage')}
            />
          </>
        )}

        {/* ==================== DATABASES TAB ==================== */}
        {activeTab === 'databases' && (
          <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
            <DatabasesTab />
          </Suspense>
        )}
      </Box>

      <ShareResourceDialog
        open={shareTarget !== null}
        onClose={() => setShareTarget(null)}
        resourceName={shareTarget?.name ?? ''}
        tenantId={shareTarget?.tenantId ?? null}
        sharedWithTenants={shareTarget?.sharedWith ?? []}
        onSave={async (sharedWithTenants, tenantId) => {
          if (!shareTarget) return
          const result = shareTarget.kind === 'dump'
            ? await updateDumpSharing(shareTarget.id, sharedWithTenants, tenantId)
            : await updateSnapshotSharing(shareTarget.id, sharedWithTenants, tenantId)
          if (!result.success) throw new Error(result.error)
          notify(t('tenants.sharing.updated'), 'success')
          if (shareTarget.kind === 'dump') loadDumps(); else loadSnapshots()
        }}
      />

      {/* Dump context menu */}
      <Menu
        open={Boolean(dumpMenu.contextMenuPos) && dumpMenu.target !== null}
        onClose={() => dumpMenu.close()}
        anchorReference="anchorPosition"
        anchorPosition={dumpMenu.contextMenuPos ?? undefined}
        slotProps={{ ...dumpMenu.menuSlotProps, paper: { sx: { minWidth: 200 } } }}
      >
        {dumpMenu.target && [
          <MenuItem
            key="download"
            component="a"
            href={`/api/database/dumps/download/${dumpMenu.target.id}`}
            onClick={() => dumpMenu.close()}
          >
            <ListItemIcon><Download fontSize="small" color="primary" /></ListItemIcon>
            <ListItemText>{t('common.download')}</ListItemText>
          </MenuItem>,
          canDbOperate && (
            <MenuItem
              key="restore"
              onClick={() => {
                if (!dumpMenu.target) return
                handleRestoreClick(dumpMenu.target)
                dumpMenu.close()
              }}
            >
              <ListItemIcon><Restore fontSize="small" color="success" /></ListItemIcon>
              <ListItemText>{t('common.restore')}</ListItemText>
            </MenuItem>
          ),
          (canDbOperate || canDbDelete) && <Divider key="divider" />,
          canDbDelete && (
            <MenuItem
              key="delete"
              onClick={() => {
                if (!dumpMenu.target) return
                handleDeleteClick(dumpMenu.target)
                dumpMenu.close()
              }}
              sx={{ color: 'error.main' }}
            >
              <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
              <ListItemText>{t('common.delete')}</ListItemText>
            </MenuItem>
          ),
        ]}
      </Menu>

      {/* Snapshot context menu */}
      <Menu
        open={Boolean(snapMenu.contextMenuPos) && snapMenu.target !== null}
        onClose={() => snapMenu.close()}
        anchorReference="anchorPosition"
        anchorPosition={snapMenu.contextMenuPos ?? undefined}
        slotProps={{ ...snapMenu.menuSlotProps, paper: { sx: { minWidth: 200 } } }}
      >
        {snapMenu.target && [
          <MenuItem
            key="download"
            component="a"
            href={`/api/database/snapshots/download/${snapMenu.target.id}`}
            onClick={() => snapMenu.close()}
          >
            <ListItemIcon><Download fontSize="small" color="primary" /></ListItemIcon>
            <ListItemText>{t('common.download')}</ListItemText>
          </MenuItem>,
          canDbOperate && (
            <MenuItem
              key="restore"
              onClick={() => {
                setRestoreSnapshot(snapMenu.target)
                setRestoreSnapOpen(true)
                snapMenu.close()
              }}
            >
              <ListItemIcon><Restore fontSize="small" color="success" /></ListItemIcon>
              <ListItemText>{t('common.restore')}</ListItemText>
            </MenuItem>
          ),
          (canDbOperate || canDbDelete) && <Divider key="divider" />,
          canDbDelete && (
            <MenuItem
              key="delete"
              onClick={() => {
                if (!snapMenu.target) return
                handleSnapDeleteClick(snapMenu.target)
                snapMenu.close()
              }}
              sx={{ color: 'error.main' }}
            >
              <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
              <ListItemText>{t('common.delete')}</ListItemText>
            </MenuItem>
          ),
        ]}
      </Menu>

      {/* ==================== MODALS ==================== */}
      <EditExpirationDialog
        open={expEdit.open}
        title={expEdit.title}
        currentExpiresAt={expEdit.current}
        onClose={expEdit.closeEdit}
        onSave={expEdit.handler ?? (async () => ({ success: false, error: 'No handler' }))}
      />

      <UploadDumpModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={loadDumps}
        existingFilenames={dumps.map((d) => d.originalFilename)}
      />

      <RestoreDumpModal
        open={restoreOpen}
        dump={restoreDump}
        onClose={() => {
          setRestoreOpen(false)
          setRestoreDump(null)
        }}
        onRestored={loadDumps}
      />

      <CreateSnapshotModal
        open={snapshotOpen}
        onClose={() => setSnapshotOpen(false)}
        onCreated={loadSnapshots}
      />

      <RestoreDumpModal
        open={restoreSnapOpen}
        dump={null}
        snapshot={restoreSnapshot}
        onClose={() => {
          setRestoreSnapOpen(false)
          setRestoreSnapshot(null)
        }}
        onRestored={loadSnapshots}
      />

      {/* Unified delete dialog */}
      <PasswordConfirmDialog
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        onConfirm={handleDeleteConfirm}
        title={getDeleteDialogTitle()}
        message={getDeleteDialogMessage()}
      />

      {/* Cleanup by idle time dialog */}
      <Dialog open={cleanup.target !== null} onClose={cleanup.close} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <CleaningServices sx={{ mr: 1, verticalAlign: 'middle' }} />
          {cleanup.target === 'dump' ? t('database.cleanUpDumps') : t('database.cleanUpSnapshots')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Typography sx={{ mb: 2 }}>
            {cleanup.target === 'dump' ? t('database.cleanUpDumpsDesc') : t('database.cleanUpSnapshotsDesc')}
          </Typography>

          <Alert severity="info" icon={<Warning />} sx={{ mb: 3 }}>
            {t('database.idleTrackingWarning')}
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
                {t('database.cleanupAffected', { count: cleanupCandidates.length })}
              </Typography>
              <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                {cleanupCandidates.map((item) => (
                  <li key={'id' in item ? item.id : ''}>
                    <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                      {cleanup.target === 'dump'
                        ? (item as DatabaseDump).originalFilename
                        : (item as DatabaseSnapshot).label || (item as DatabaseSnapshot).sourceDatabaseName}
                      <Typography component="span" variant="caption" sx={{ ml: 1, color: 'text.secondary' }}>
                        ({formatBytes((item as DatabaseDump | DatabaseSnapshot).fileSize)})
                      </Typography>
                    </Typography>
                  </li>
                ))}
              </Box>
            </Alert>
          ) : (
            <Alert severity="success" sx={{ mb: 3 }}>
              {t('database.cleanupNoneAffected')}
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
            />
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={cleanup.close} color="inherit" disabled={cleanup.loading}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={cleanup.confirm}
            disabled={cleanup.loading || (!rbacEnabled && !cleanup.password)}
            startIcon={cleanup.loading ? <CircularProgress size={20} /> : <CleaningServices />}
          >
            {cleanup.loading ? t('common.deleting') : t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>

      <PasswordConfirmDialog
        open={dumpEdit.passwordOpen}
        title={t('database.editMetadataTitle')}
        message={t('database.editMetadataPasswordMessage')}
        confirmLabel={t('common.save')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Edit />}
        onConfirm={async (password) => {
          await dumpEdit.saveEdit(password)
          dumpEdit.setPasswordOpen(false)
        }}
        onClose={() => { dumpEdit.setPasswordOpen(false); dumpEdit.cancelEdit() }}
      />

      <PasswordConfirmDialog
        open={snapEdit.passwordOpen}
        title={t('database.editMetadataTitle')}
        message={t('database.editMetadataPasswordMessage')}
        confirmLabel={t('common.save')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Edit />}
        onConfirm={async (password) => {
          await snapEdit.saveEdit(password)
          snapEdit.setPasswordOpen(false)
        }}
        onClose={() => { snapEdit.setPasswordOpen(false); snapEdit.cancelEdit() }}
      />
    </>
  )
}
