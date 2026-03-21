import { useState, useEffect, useCallback, useMemo } from 'react'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { listDumps, deleteDump, deleteDumpsBulk, getStorageInfo, getActiveRestores, updateDumpExpiration, updateDumpMetadata, cleanupIdleDumps, type ActiveRestore } from '../services/dumpService'
import { listSnapshots, deleteSnapshot, deleteSnapshotsBulk, getSnapshotStorageInfo, getActiveSnapshots, updateSnapshotExpiration, updateSnapshotMetadata, cleanupIdleSnapshots, type ActiveSnapshot } from '../services/snapshotService'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import UploadDumpModal from '../components/UploadDumpModal'
import RestoreDumpModal from '../components/RestoreDumpModal'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import EditExpirationDialog from '../components/EditExpirationDialog'
import PasswordConfirmDialog from '../components/PasswordConfirmDialog'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
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
  ListItemIcon,
  ListItemText,
} from '@mui/material'
import { Search, Delete, CloudUpload, Download, Restore, Timer, Storage, InsertDriveFile, CameraAlt, InfoOutlined, CleaningServices, Warning, Edit, Check, Close } from '@mui/icons-material'

type PendingDelete =
  | { kind: 'dump'; dump: DatabaseDump }
  | { kind: 'dumpBulk'; ids: string[] }
  | { kind: 'snapshot'; snapshot: DatabaseSnapshot }
  | { kind: 'snapshotBulk'; ids: string[] }

export default function DatabasePage() {
  const { notify } = useNotification()
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx, theadCheckboxSx } = useTableHeaderTheme()
  const [activeTab, setActiveTab] = useState(0)

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
  const [editingDumpId, setEditingDumpId] = useState<string | null>(null)
  const [editVersion, setEditVersion] = useState('')
  const [editDatabase, setEditDatabase] = useState('')
  const [editSaving, setEditSaving] = useState(false)
  const [metadataPassword, setMetadataPassword] = useState('')
  const [metadataPasswordOpen, setMetadataPasswordOpen] = useState(false)

  const [editDescription, setEditDescription] = useState('')

  function startEditDump(dump: DatabaseDump) {
    setEditingDumpId(dump.id)
    setEditVersion(dump.version || '')
    setEditDatabase(dump.databaseName || '')
    setEditDescription(dump.description || '')
  }

  function cancelEditDump() {
    setEditingDumpId(null)
    setEditVersion('')
    setEditDatabase('')
    setEditDescription('')
  }

  async function saveEditDump(password?: string) {
    if (!editingDumpId) return
    const pw = password ?? metadataPassword
    if (!pw) {
      setMetadataPasswordOpen(true)
      return
    }
    setEditSaving(true)
    const result = await updateDumpMetadata(editingDumpId, editVersion, editDatabase, pw, editDescription)
    setEditSaving(false)
    if (result.success) {
      setDumps(prev => prev.map(d => d.id === editingDumpId ? { ...d, version: editVersion, databaseName: editDatabase, description: editDescription || undefined } : d))
      setEditingDumpId(null)
      setMetadataPassword('')
    } else {
      if (result.error?.includes('password') || result.error?.includes('Password')) {
        setMetadataPassword('')
        setMetadataPasswordOpen(true)
      }
      notify(result.error || 'Failed to update.', 'error')
    }
  }

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
  const [editingSnapId, setEditingSnapId] = useState<string | null>(null)
  const [editSnapLabel, setEditSnapLabel] = useState('')
  const [editSnapDescription, setEditSnapDescription] = useState('')
  const [editSnapSaving, setEditSnapSaving] = useState(false)
  const [snapMetadataPassword, setSnapMetadataPassword] = useState('')
  const [snapMetadataPasswordOpen, setSnapMetadataPasswordOpen] = useState(false)

  function startEditSnap(snap: DatabaseSnapshot) {
    setEditingSnapId(snap.id)
    setEditSnapLabel(snap.label || '')
    setEditSnapDescription(snap.description || '')
  }

  function cancelEditSnap() {
    setEditingSnapId(null)
    setEditSnapLabel('')
    setEditSnapDescription('')
  }

  async function saveEditSnap(password?: string) {
    if (!editingSnapId) return
    const pw = password ?? snapMetadataPassword
    if (!pw) {
      setSnapMetadataPasswordOpen(true)
      return
    }
    setEditSnapSaving(true)
    const result = await updateSnapshotMetadata(editingSnapId, editSnapLabel, pw, editSnapDescription)
    setEditSnapSaving(false)
    if (result.success) {
      setSnapshots(prev => prev.map(s => s.id === editingSnapId ? { ...s, label: editSnapLabel, description: editSnapDescription || undefined } : s))
      setEditingSnapId(null)
      setSnapMetadataPassword('')
    } else {
      if (result.error?.includes('password') || result.error?.includes('Password')) {
        setSnapMetadataPassword('')
        setSnapMetadataPasswordOpen(true)
      }
      notify(result.error || 'Failed to update.', 'error')
    }
  }

  // --- Delete dialog state (unified) ---
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)

  // --- Edit expiration state ---
  const [expirationEditOpen, setExpirationEditOpen] = useState(false)
  const [expirationEditTitle, setExpirationEditTitle] = useState('')
  const [expirationEditCurrent, setExpirationEditCurrent] = useState<string | null>(null)
  const [expirationEditHandler, setExpirationEditHandler] = useState<
    ((expiresAt: string | null, password: string) => Promise<{ success: boolean; error?: string }>) | null
  >(null)

  // --- Filter state ---
  const [showNeverUsedDumps, setShowNeverUsedDumps] = useState(false)
  const [showNeverUsedSnaps, setShowNeverUsedSnaps] = useState(false)

  // --- Cleanup by idle time state ---
  const [cleanupTarget, setCleanupTarget] = useState<'dump' | 'snapshot' | null>(null)
  const [cleanupPassword, setCleanupPassword] = useState('')
  const [cleanupMinDays, setCleanupMinDays] = useState(30)
  const [cleanupLoading, setCleanupLoading] = useState(false)
  const [cleanupError, setCleanupError] = useState('')

  // --- Context menu state ---
  const [dumpContextPos, setDumpContextPos] = useState<{ top: number; left: number } | null>(null)
  const [dumpContextItem, setDumpContextItem] = useState<DatabaseDump | null>(null)
  const [snapContextPos, setSnapContextPos] = useState<{ top: number; left: number } | null>(null)
  const [snapContextItem, setSnapContextItem] = useState<DatabaseSnapshot | null>(null)

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
    { key: 'action', label: t('database.dumpColumns.actions') },
  ], [t])

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
    { key: 'action', label: t('database.snapColumns.actions') },
  ], [t])

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

  useEffect(() => {
    loadDumps()
    loadSnapshots()
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
      [d.originalFilename, d.databaseName ?? '', d.version ?? '', d.format, formatBytes(d.fileSize), d.description ?? '']
        .some((v) => v.toLowerCase().includes(filter.toLowerCase())),
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      if (sortKey === 'fileSize') {
        const cmp = a.fileSize - b.fileSize
        return sortDir === 'asc' ? cmp : -cmp
      }
      const va = String((a as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
      const vb = String((b as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
      const cmp = va.localeCompare(vb)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [dumps, filter, sortKey, sortDir, showNeverUsedDumps])

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
    } catch {
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
    setExpirationEditTitle(dump.originalFilename)
    setExpirationEditCurrent(dump.expiresAt ?? null)
    setExpirationEditHandler(() => async (expiresAt: string | null, password: string) => {
      const result = await updateDumpExpiration(dump.id, expiresAt, password)
      if (result.success) loadDumps()
      return result
    })
    setExpirationEditOpen(true)
  }

  function handleEditSnapExpiration(snap: DatabaseSnapshot) {
    setExpirationEditTitle(snap.label || snap.sourceDatabaseName)
    setExpirationEditCurrent(snap.expiresAt ?? null)
    setExpirationEditHandler(() => async (expiresAt: string | null, password: string) => {
      const result = await updateSnapshotExpiration(snap.id, expiresAt, password)
      if (result.success) loadSnapshots()
      return result
    })
    setExpirationEditOpen(true)
  }

  const filteredSnapshots = useMemo(() => {
    let data = showNeverUsedSnaps ? snapshots.filter(s => !s.lastUsedAt) : snapshots
    const result = data.filter((s) =>
      [s.label ?? '', s.repository, s.sourceDatabaseName, s.containerName ?? '', s.format, formatBytes(s.fileSize), s.description ?? '']
        .some((v) => v.toLowerCase().includes(snapFilter.toLowerCase())),
    )
    if (!snapSortKey) return result
    return [...result].sort((a, b) => {
      if (snapSortKey === 'fileSize') {
        const cmp = a.fileSize - b.fileSize
        return snapSortDir === 'asc' ? cmp : -cmp
      }
      const va = String((a as unknown as Record<string, unknown>)[snapSortKey] ?? '').toLowerCase()
      const vb = String((b as unknown as Record<string, unknown>)[snapSortKey] ?? '').toLowerCase()
      const cmp = va.localeCompare(vb)
      return snapSortDir === 'asc' ? cmp : -cmp
    })
  }, [snapshots, snapFilter, snapSortKey, snapSortDir, showNeverUsedSnaps])

  function openCleanupDialog(target: 'dump' | 'snapshot') {
    setCleanupTarget(target)
    setCleanupPassword('')
    setCleanupMinDays(30)
    setCleanupError('')
  }

  function closeCleanupDialog() {
    if (!cleanupLoading) {
      setCleanupTarget(null)
      setCleanupPassword('')
      setCleanupError('')
    }
  }

  async function handleCleanupConfirm() {
    setCleanupLoading(true)
    setCleanupError('')
    try {
      const result = cleanupTarget === 'dump'
        ? await cleanupIdleDumps(cleanupPassword, cleanupMinDays)
        : await cleanupIdleSnapshots(cleanupPassword, cleanupMinDays)

      if (result.success) {
        setCleanupTarget(null)
        setCleanupPassword('')
        notify(t('database.cleanupComplete', { count: result.deleted ?? 0 }), 'success')
        if (cleanupTarget === 'dump') loadDumps()
        else loadSnapshots()
      } else {
        setCleanupError(result.error || 'Unknown error')
      }
    } catch (e) {
      setCleanupError(e instanceof Error ? e.message : String(e))
    } finally {
      setCleanupLoading(false)
    }
  }

  const currentStorageInfo = activeTab === 0 ? storageInfo : snapStorageInfo
  const currentFileLabel = activeTab === 0
    ? (currentStorageInfo?.fileCount === 1 ? t('database.dumpFile') : t('database.dumpFiles'))
    : (currentStorageInfo?.fileCount === 1 ? t('database.snapshotFile') : t('database.snapshotFiles'))

  return (
    <>
      <HeroBanner linkTo="/" linkLabel={t('hero.exploreContainers')} />

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 1 }}>
          {t('database.title')}
        </Typography>

        <Tabs value={activeTab} onChange={(_e, v) => setActiveTab(v)} sx={{ mb: 3 }}>
          <Tab label={t('database.dumpsTab', { count: dumps.length })} />
          <Tab label={t('database.snapshotsTab', { count: snapshots.length })} />
        </Tabs>

        {currentStorageInfo && currentStorageInfo.maxBytes > 0 && (
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

        {activeRestores.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>{t('database.restoreInProgress')}</AlertTitle>
            {activeRestores.map((r, i) => (
              <Typography key={i} variant="body2">
                <span dangerouslySetInnerHTML={{ __html: t('database.restoringInto', { filename: r.dumpFilename, database: r.targetDatabase, repository: r.repository }) }} />
              </Typography>
            ))}
          </Alert>
        )}

        {activeTab === 1 && activeSnaps.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>{t('database.snapshotInProgress')}</AlertTitle>
            {activeSnaps.map((s, i) => (
              <Typography key={i} variant="body2">
                <span dangerouslySetInnerHTML={{ __html: t('database.creatingSnapshotOf', { database: s.sourceDatabaseName, repository: s.repository }) }} />
              </Typography>
            ))}
          </Alert>
        )}

        {/* ==================== DUMPS TAB ==================== */}
        {activeTab === 0 && (
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
              {selected.size > 0 && (
                <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleBulkDeleteClick} size="small">
                  {t('common.delete')} ({selected.size})
                </Button>
              )}
              <Tooltip title={t('database.cleanUpByIdleDesc')}>
                <Button variant="contained" color="warning" startIcon={<CleaningServices />} onClick={() => openCleanupDialog('dump')} disabled={dumps.length === 0} size="small">
                  {t('database.cleanUpByIdle')}
                </Button>
              </Tooltip>
              <Button variant="contained" color="primary" startIcon={<CloudUpload />} onClick={() => setUploadOpen(true)} size="small">
                {t('database.uploadDump')}
              </Button>
            </Box>
            <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2, overflowX: 'auto' }}>
              <Table aria-label="Database dumps">
                <TableHead>
                  <TableRow sx={{ bgcolor: theadBg }}>
                    <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                      <Checkbox
                        checked={filteredDumps.length > 0 && selected.size === filteredDumps.length}
                        indeterminate={selected.size > 0 && selected.size < filteredDumps.length}
                        onChange={toggleSelectAll}
                        sx={theadCheckboxSx}
                      />
                    </TableCell>
                    {DUMP_COLUMNS.map((col) => (
                      <TableCell key={col.key} sx={{ color: theadColor, fontWeight: 600 }}>
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
                  {filteredDumps.map((dump) => (
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
                      selected={selected.has(dump.id)}
                      onContextMenu={(e) => {
                        e.preventDefault()
                        setDumpContextPos({ top: e.clientY, left: e.clientX })
                        setDumpContextItem(dump)
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
                        {editingDumpId === dump.id && (
                          <TextField
                            value={editDescription}
                            onChange={(e) => setEditDescription(e.target.value)}
                            size="small"
                            variant="standard"
                            placeholder={t('common.description')}
                            multiline
                            maxRows={3}
                            fullWidth
                            sx={{ mt: 0.5 }}
                            onKeyDown={(e) => { if (e.key === 'Escape') cancelEditDump() }}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        {editingDumpId === dump.id ? (
                          <TextField
                            value={editVersion}
                            onChange={(e) => setEditVersion(e.target.value)}
                            size="small"
                            variant="standard"
                            placeholder="-"
                            autoFocus
                            sx={{ width: 100 }}
                            onKeyDown={(e) => { if (e.key === 'Enter') saveEditDump(); if (e.key === 'Escape') cancelEditDump() }}
                          />
                        ) : (
                          <Box
                            onClick={() => startEditDump(dump)}
                            sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.5, '&:hover .edit-icon': { opacity: 1 } }}
                          >
                            {dump.version || '-'}
                            <Edit className="edit-icon" sx={{ fontSize: 14, opacity: 0, color: 'text.secondary', transition: 'opacity 0.2s' }} />
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>
                        {editingDumpId === dump.id ? (
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <TextField
                              value={editDatabase}
                              onChange={(e) => setEditDatabase(e.target.value)}
                              size="small"
                              variant="standard"
                              placeholder="-"
                              sx={{ width: 100 }}
                              onKeyDown={(e) => { if (e.key === 'Escape') cancelEditDump() }}
                            />
                            <IconButton size="small" onClick={() => saveEditDump()} disabled={editSaving} color="success">
                              {editSaving ? <CircularProgress size={14} /> : <Check sx={{ fontSize: 16 }} />}
                            </IconButton>
                            <IconButton size="small" onClick={cancelEditDump} disabled={editSaving}>
                              <Close sx={{ fontSize: 16 }} />
                            </IconButton>
                          </Box>
                        ) : (
                          <Box
                            onClick={() => startEditDump(dump)}
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
                          onClick={() => handleEditDumpExpiration(dump)}
                          sx={{ cursor: 'pointer' }}
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
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 0.25 }}>
                          <Tooltip title={t('common.download')}>
                            <IconButton size="small" color="primary" component="a" href={`/api/database/dumps/download/${dump.id}`}>
                              <Download />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title={t('common.restore')}>
                            <IconButton size="small" color="success" onClick={() => handleRestoreClick(dump)}>
                              <Restore />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title={t('common.delete')}>
                            <IconButton size="small" color="error" onClick={() => handleDeleteClick(dump)}>
                              <Delete />
                            </IconButton>
                          </Tooltip>
                        </Box>
                      </TableCell>
                    </TableRow>
                    </Tooltip>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}

        {/* ==================== SNAPSHOTS TAB ==================== */}
        {activeTab === 1 && (
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
              {snapSelected.size > 0 && (
                <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleSnapBulkDeleteClick} size="small">
                  {t('common.delete')} ({snapSelected.size})
                </Button>
              )}
              <Tooltip title={t('database.cleanUpByIdleDesc')}>
                <Button variant="contained" color="warning" startIcon={<CleaningServices />} onClick={() => openCleanupDialog('snapshot')} disabled={snapshots.length === 0} size="small">
                  {t('database.cleanUpByIdle')}
                </Button>
              </Tooltip>
              <Button variant="contained" color="primary" startIcon={<CameraAlt />} onClick={() => setSnapshotOpen(true)} size="small">
                {t('database.createSnapshot')}
              </Button>
            </Box>
            <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2, overflowX: 'auto' }}>
              <Table aria-label="Database snapshots">
                <TableHead>
                  <TableRow sx={{ bgcolor: theadBg }}>
                    <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                      <Checkbox
                        checked={filteredSnapshots.length > 0 && snapSelected.size === filteredSnapshots.length}
                        indeterminate={snapSelected.size > 0 && snapSelected.size < filteredSnapshots.length}
                        onChange={toggleSnapSelectAll}
                        sx={theadCheckboxSx}
                      />
                    </TableCell>
                    {SNAP_COLUMNS.map((col) => (
                      <TableCell key={col.key} sx={{ color: theadColor, fontWeight: 600 }}>
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
                  {filteredSnapshots.map((snap) => (
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
                      selected={snapSelected.has(snap.id)}
                      onContextMenu={(e) => {
                        e.preventDefault()
                        setSnapContextPos({ top: e.clientY, left: e.clientX })
                        setSnapContextItem(snap)
                      }}
                    >
                      <TableCell padding="checkbox">
                        <Checkbox checked={snapSelected.has(snap.id)} onChange={() => toggleSnapSelect(snap.id)} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                        {editingSnapId === snap.id ? (
                          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <TextField
                                value={editSnapLabel}
                                onChange={(e) => setEditSnapLabel(e.target.value)}
                                size="small"
                                variant="standard"
                                placeholder="-"
                                autoFocus
                                sx={{ width: 140 }}
                                onKeyDown={(e) => { if (e.key === 'Escape') cancelEditSnap() }}
                              />
                              <IconButton size="small" onClick={() => saveEditSnap()} disabled={editSnapSaving} color="success">
                                {editSnapSaving ? <CircularProgress size={14} /> : <Check sx={{ fontSize: 16 }} />}
                              </IconButton>
                              <IconButton size="small" onClick={cancelEditSnap} disabled={editSnapSaving}>
                                <Close sx={{ fontSize: 16 }} />
                              </IconButton>
                            </Box>
                            <TextField
                              value={editSnapDescription}
                              onChange={(e) => setEditSnapDescription(e.target.value)}
                              size="small"
                              variant="standard"
                              placeholder={t('common.description')}
                              multiline
                              maxRows={3}
                              fullWidth
                              onKeyDown={(e) => { if (e.key === 'Escape') cancelEditSnap() }}
                            />
                          </Box>
                        ) : (
                          <Box
                            onClick={() => startEditSnap(snap)}
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
                          onClick={() => handleEditSnapExpiration(snap)}
                          sx={{ cursor: 'pointer' }}
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
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 0.25 }}>
                          <Tooltip title={t('common.download')}>
                            <IconButton size="small" color="primary" component="a" href={`/api/database/snapshots/download/${snap.id}`}>
                              <Download />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title={t('common.restore')}>
                            <IconButton size="small" color="success" onClick={() => { setRestoreSnapshot(snap); setRestoreSnapOpen(true) }}>
                              <Restore />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title={t('common.delete')}>
                            <IconButton size="small" color="error" onClick={() => handleSnapDeleteClick(snap)}>
                              <Delete />
                            </IconButton>
                          </Tooltip>
                        </Box>
                      </TableCell>
                    </TableRow>
                    </Tooltip>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}
      </Box>

      {/* Dump context menu */}
      <Menu
        open={Boolean(dumpContextPos) && dumpContextItem !== null}
        onClose={() => { setDumpContextPos(null); setDumpContextItem(null) }}
        anchorReference="anchorPosition"
        anchorPosition={dumpContextPos ?? undefined}
        slotProps={{ paper: { sx: { minWidth: 200 } } }}
      >
        {dumpContextItem && [
          <MenuItem
            key="download"
            component="a"
            href={`/api/database/dumps/download/${dumpContextItem.id}`}
            onClick={() => { setDumpContextPos(null); setDumpContextItem(null) }}
          >
            <ListItemIcon><Download fontSize="small" color="primary" /></ListItemIcon>
            <ListItemText>{t('common.download')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="restore"
            onClick={() => {
              handleRestoreClick(dumpContextItem)
              setDumpContextPos(null); setDumpContextItem(null)
            }}
          >
            <ListItemIcon><Restore fontSize="small" color="success" /></ListItemIcon>
            <ListItemText>{t('common.restore')}</ListItemText>
          </MenuItem>,
          <Divider key="divider" />,
          <MenuItem
            key="delete"
            onClick={() => {
              handleDeleteClick(dumpContextItem)
              setDumpContextPos(null); setDumpContextItem(null)
            }}
            sx={{ color: 'error.main' }}
          >
            <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
            <ListItemText>{t('common.delete')}</ListItemText>
          </MenuItem>,
        ]}
      </Menu>

      {/* Snapshot context menu */}
      <Menu
        open={Boolean(snapContextPos) && snapContextItem !== null}
        onClose={() => { setSnapContextPos(null); setSnapContextItem(null) }}
        anchorReference="anchorPosition"
        anchorPosition={snapContextPos ?? undefined}
        slotProps={{ paper: { sx: { minWidth: 200 } } }}
      >
        {snapContextItem && [
          <MenuItem
            key="download"
            component="a"
            href={`/api/database/snapshots/download/${snapContextItem.id}`}
            onClick={() => { setSnapContextPos(null); setSnapContextItem(null) }}
          >
            <ListItemIcon><Download fontSize="small" color="primary" /></ListItemIcon>
            <ListItemText>{t('common.download')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="restore"
            onClick={() => {
              setRestoreSnapshot(snapContextItem)
              setRestoreSnapOpen(true)
              setSnapContextPos(null); setSnapContextItem(null)
            }}
          >
            <ListItemIcon><Restore fontSize="small" color="success" /></ListItemIcon>
            <ListItemText>{t('common.restore')}</ListItemText>
          </MenuItem>,
          <Divider key="divider" />,
          <MenuItem
            key="delete"
            onClick={() => {
              handleSnapDeleteClick(snapContextItem)
              setSnapContextPos(null); setSnapContextItem(null)
            }}
            sx={{ color: 'error.main' }}
          >
            <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
            <ListItemText>{t('common.delete')}</ListItemText>
          </MenuItem>,
        ]}
      </Menu>

      {/* ==================== MODALS ==================== */}
      <EditExpirationDialog
        open={expirationEditOpen}
        title={expirationEditTitle}
        currentExpiresAt={expirationEditCurrent}
        onClose={() => setExpirationEditOpen(false)}
        onSave={expirationEditHandler ?? (async () => ({ success: false, error: 'No handler' }))}
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
      <Dialog open={cleanupTarget !== null} onClose={closeCleanupDialog} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <CleaningServices sx={{ mr: 1, verticalAlign: 'middle' }} />
          {cleanupTarget === 'dump' ? t('database.cleanUpDumps') : t('database.cleanUpSnapshots')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Typography sx={{ mb: 2 }}>
            {cleanupTarget === 'dump' ? t('database.cleanUpDumpsDesc') : t('database.cleanUpSnapshotsDesc')}
          </Typography>

          <Alert severity="info" icon={<Warning />} sx={{ mb: 3 }}>
            {t('database.idleTrackingWarning')}
          </Alert>

          <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
            {t('database.minDaysLabel')}
          </Typography>
          <Box sx={{ px: 2, mb: 3 }}>
            <Slider
              value={cleanupMinDays}
              onChange={(_, v) => setCleanupMinDays(v as number)}
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

          <TextField
            fullWidth
            type="password"
            label={t('common.operationsPassword')}
            value={cleanupPassword}
            onChange={(e) => { setCleanupPassword(e.target.value); setCleanupError('') }}
            size="small"
            autoComplete="off"
            error={!!cleanupError}
            helperText={cleanupError}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={closeCleanupDialog} color="inherit" disabled={cleanupLoading}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={handleCleanupConfirm}
            disabled={cleanupLoading || !cleanupPassword}
            startIcon={cleanupLoading ? <CircularProgress size={20} /> : <CleaningServices />}
          >
            {cleanupLoading ? t('common.deleting') : t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>

      <PasswordConfirmDialog
        open={metadataPasswordOpen}
        title={t('database.editMetadataTitle')}
        message={t('database.editMetadataPasswordMessage')}
        confirmLabel={t('common.save')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Edit />}
        onConfirm={async (password) => {
          setMetadataPassword(password)
          setMetadataPasswordOpen(false)
          await saveEditDump(password)
        }}
        onClose={() => { setMetadataPasswordOpen(false); cancelEditDump() }}
      />

      <PasswordConfirmDialog
        open={snapMetadataPasswordOpen}
        title={t('database.editMetadataTitle')}
        message={t('database.editMetadataPasswordMessage')}
        confirmLabel={t('common.save')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Edit />}
        onConfirm={async (password) => {
          setSnapMetadataPassword(password)
          setSnapMetadataPasswordOpen(false)
          await saveEditSnap(password)
        }}
        onClose={() => { setSnapMetadataPasswordOpen(false); cancelEditSnap() }}
      />
    </>
  )
}
