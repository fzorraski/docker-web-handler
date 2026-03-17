import { useState, useEffect, useCallback, useMemo } from 'react'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { listDumps, deleteDump, deleteDumpsBulk, getStorageInfo, getActiveRestores, updateDumpExpiration, type ActiveRestore } from '../services/dumpService'
import { listSnapshots, deleteSnapshot, deleteSnapshotsBulk, getSnapshotStorageInfo, getActiveSnapshots, updateSnapshotExpiration, type ActiveSnapshot } from '../services/snapshotService'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import UploadDumpModal from '../components/UploadDumpModal'
import RestoreDumpModal from '../components/RestoreDumpModal'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import EditExpirationDialog from '../components/EditExpirationDialog'
import { formatBytes, formatDate } from '../utils/format'
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
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  LinearProgress,
  Grid,
  Alert,
  AlertTitle,
  Tabs,
  Tab,
  Tooltip,
  useTheme,
} from '@mui/material'
import { Search, Delete, CloudUpload, Download, Restore, Timer, Storage, InsertDriveFile, CameraAlt, InfoOutlined } from '@mui/icons-material'

export default function DatabasePage() {
  const { notify } = useNotification()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const theadBg = isDark ? 'background.paper' : 'primary.main'
  const theadColor = isDark ? 'text.primary' : 'white'
  const theadSortSx = isDark
    ? { color: 'text.primary !important', '& .MuiTableSortLabel-icon': { color: 'text.secondary !important' } }
    : { color: 'white !important', '& .MuiTableSortLabel-icon': { color: 'white !important' } }
  const theadCheckboxSx = isDark
    ? { color: 'text.primary', '&.Mui-checked': { color: 'primary.main' }, '&.MuiCheckbox-indeterminate': { color: 'primary.main' } }
    : { color: 'white', '&.Mui-checked': { color: 'white' }, '&.MuiCheckbox-indeterminate': { color: 'white' } }
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
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [deletingDump, setDeletingDump] = useState<DatabaseDump | null>(null)
  const [deletePassword, setDeletePassword] = useState('')
  const [deleteLoading, setDeleteLoading] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [storageInfo, setStorageInfo] = useState<{ totalBytes: number; fileCount: number; maxBytes: number } | null>(null)
  const [activeRestores, setActiveRestores] = useState<ActiveRestore[]>([])

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
  const [snapDeleteDialogOpen, setSnapDeleteDialogOpen] = useState(false)
  const [deletingSnapshot, setDeletingSnapshot] = useState<DatabaseSnapshot | null>(null)
  const [snapDeletePassword, setSnapDeletePassword] = useState('')
  const [snapDeleteLoading, setSnapDeleteLoading] = useState(false)
  const [restoreSnapOpen, setRestoreSnapOpen] = useState(false)
  const [restoreSnapshot, setRestoreSnapshot] = useState<DatabaseSnapshot | null>(null)

  // --- Edit expiration state ---
  const [expirationEditOpen, setExpirationEditOpen] = useState(false)
  const [expirationEditTitle, setExpirationEditTitle] = useState('')
  const [expirationEditCurrent, setExpirationEditCurrent] = useState<string | null>(null)
  const [expirationEditHandler, setExpirationEditHandler] = useState<
    ((expiresAt: string | null, password: string) => Promise<{ success: boolean; error?: string }>) | null
  >(null)

  const DUMP_COLUMNS: { key: string; label: string }[] = [
    { key: 'originalFilename', label: 'Original Filename' },
    { key: 'version', label: 'Version' },
    { key: 'databaseName', label: 'Database' },
    { key: 'format', label: 'Format' },
    { key: 'fileSize', label: 'Size' },
    { key: 'md5Hash', label: 'MD5' },
    { key: 'uploadedAt', label: 'Uploaded At' },
    { key: 'expiresAt', label: 'Expires' },
    { key: 'action', label: 'Actions' },
  ]

  const SNAP_COLUMNS: { key: string; label: string }[] = [
    { key: 'label', label: 'Label' },
    { key: 'repository', label: 'Repository' },
    { key: 'sourceDatabaseName', label: 'Database' },
    { key: 'containerName', label: 'Container' },
    { key: 'format', label: 'Format' },
    { key: 'fileSize', label: 'Size' },
    { key: 'md5Hash', label: 'MD5' },
    { key: 'createdAt', label: 'Created At' },
    { key: 'expiresAt', label: 'Expires' },
    { key: 'action', label: 'Actions' },
  ]

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
    setDeletingDump(null)
    setDeletePassword('')
    setDeleteDialogOpen(true)
  }

  function handleRestoreClick(dump: DatabaseDump) {
    setRestoreDump(dump)
    setRestoreOpen(true)
  }

  function handleDeleteClick(dump: DatabaseDump) {
    setDeletingDump(dump)
    setDeletePassword('')
    setDeleteDialogOpen(true)
  }

  const isBulkDelete = !deletingDump && selected.size > 0

  async function handleDeleteConfirm() {
    setDeleteLoading(true)
    try {
      if (isBulkDelete) {
        const result = await deleteDumpsBulk([...selected], deletePassword)
        if (result.success) {
          notify(`${result.deleted} dump${result.deleted === 1 ? '' : 's'} deleted successfully.`, 'success')
          setSelected(new Set())
          setDeleteDialogOpen(false)
          loadDumps()
        } else {
          notify(result.error || 'Delete failed.', 'error')
        }
      } else if (deletingDump) {
        const result = await deleteDump(deletingDump.id, deletePassword)
        if (result.success) {
          notify('Dump deleted successfully.', 'success')
          setDeleteDialogOpen(false)
          setDeletingDump(null)
          loadDumps()
        } else {
          notify(result.error || 'Delete failed.', 'error')
        }
      }
    } catch {
      notify('An unexpected error occurred.', 'error')
    } finally {
      setDeleteLoading(false)
    }
  }

  const filteredDumps = useMemo(() => {
    const result = dumps.filter((d) =>
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
  }, [dumps, filter, sortKey, sortDir])

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
    setDeletingSnapshot(null)
    setSnapDeletePassword('')
    setSnapDeleteDialogOpen(true)
  }

  function handleSnapDeleteClick(snap: DatabaseSnapshot) {
    setDeletingSnapshot(snap)
    setSnapDeletePassword('')
    setSnapDeleteDialogOpen(true)
  }

  const isSnapBulkDelete = !deletingSnapshot && snapSelected.size > 0

  async function handleSnapDeleteConfirm() {
    setSnapDeleteLoading(true)
    try {
      if (isSnapBulkDelete) {
        const result = await deleteSnapshotsBulk([...snapSelected], snapDeletePassword)
        if (result.success) {
          notify(`${result.deleted} snapshot${result.deleted === 1 ? '' : 's'} deleted successfully.`, 'success')
          setSnapSelected(new Set())
          setSnapDeleteDialogOpen(false)
          loadSnapshots()
        } else {
          notify(result.error || 'Delete failed.', 'error')
        }
      } else if (deletingSnapshot) {
        const result = await deleteSnapshot(deletingSnapshot.id, snapDeletePassword)
        if (result.success) {
          notify('Snapshot deleted successfully.', 'success')
          setSnapDeleteDialogOpen(false)
          setDeletingSnapshot(null)
          loadSnapshots()
        } else {
          notify(result.error || 'Delete failed.', 'error')
        }
      }
    } catch {
      notify('An unexpected error occurred.', 'error')
    } finally {
      setSnapDeleteLoading(false)
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
    const result = snapshots.filter((s) =>
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
  }, [snapshots, snapFilter, snapSortKey, snapSortDir])

  const currentStorageInfo = activeTab === 0 ? storageInfo : snapStorageInfo
  const currentFileLabel = activeTab === 0 ? 'dump' : 'snapshot'

  return (
    <>
      <HeroBanner linkTo="/" linkLabel="Explore Containers" />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
          <Typography variant="h4" fontWeight="bold">
            Database Files
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            {activeTab === 0 && selected.size > 0 && (
              <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleBulkDeleteClick}>
                Delete ({selected.size})
              </Button>
            )}
            {activeTab === 1 && snapSelected.size > 0 && (
              <Button variant="contained" color="error" startIcon={<Delete />} onClick={handleSnapBulkDeleteClick}>
                Delete ({snapSelected.size})
              </Button>
            )}
            {activeTab === 0 && (
              <Button variant="contained" color="primary" startIcon={<CloudUpload />} onClick={() => setUploadOpen(true)}>
                Upload Dump
              </Button>
            )}
            {activeTab === 1 && (
              <Button variant="contained" color="primary" startIcon={<CameraAlt />} onClick={() => setSnapshotOpen(true)}>
                Create Snapshot
              </Button>
            )}
          </Box>
        </Box>

        <Tabs value={activeTab} onChange={(_e, v) => setActiveTab(v)} sx={{ mb: 3 }}>
          <Tab label={`Dumps (${dumps.length})`} />
          <Tab label={`Snapshots (${snapshots.length})`} />
        </Tabs>

        {currentStorageInfo && currentStorageInfo.maxBytes > 0 && (
          <Paper elevation={2} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
            <Grid container spacing={3} alignItems="center">
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Storage color="primary" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" fontWeight="bold" lineHeight={1.2}>
                      {formatBytes(currentStorageInfo.totalBytes)}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      of {formatBytes(currentStorageInfo.maxBytes)} used
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <InsertDriveFile color="action" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" fontWeight="bold" lineHeight={1.2}>
                      {currentStorageInfo.fileCount}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {currentStorageInfo.fileCount === 1 ? `${currentFileLabel} file` : `${currentFileLabel} files`}
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" color="text.secondary">Storage usage</Typography>
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
            <AlertTitle>Restore in progress</AlertTitle>
            {activeRestores.map((r, i) => (
              <Typography key={i} variant="body2">
                Restoring <strong>{r.dumpFilename}</strong> into <strong>{r.targetDatabase}</strong> ({r.repository})
              </Typography>
            ))}
          </Alert>
        )}

        {activeTab === 1 && activeSnaps.length > 0 && (
          <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
            <AlertTitle>Snapshot in progress</AlertTitle>
            {activeSnaps.map((s, i) => (
              <Typography key={i} variant="body2">
                Creating snapshot of <strong>{s.sourceDatabaseName}</strong> ({s.repository})
              </Typography>
            ))}
          </Alert>
        )}

        {/* ==================== DUMPS TAB ==================== */}
        {activeTab === 0 && (
          <>
            <TextField
              fullWidth
              placeholder="Search dumps..."
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search color="action" /></InputAdornment> } }}
            />
            <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2 }}>
              <Table>
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
                        No dumps found
                      </TableCell>
                    </TableRow>
                  )}
                  {filteredDumps.map((dump) => (
                    <Tooltip
                      key={dump.id}
                      title={dump.description ? (
                        <Box sx={{ p: 0.5 }}>
                          <Typography variant="caption" fontWeight={700} sx={{ display: 'block', mb: 0.5, opacity: 0.8 }}>
                            Description
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
                            '& .MuiTooltip-arrow': { color: 'primary.dark' },
                          },
                        },
                      }}
                    >
                    <TableRow hover selected={selected.has(dump.id)}>
                      <TableCell padding="checkbox">
                        <Checkbox checked={selected.has(dump.id)} onChange={() => toggleSelect(dump.id)} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                          {dump.originalFilename}
                          {dump.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                        </Box>
                      </TableCell>
                      <TableCell>{dump.version || '-'}</TableCell>
                      <TableCell>{dump.databaseName || '-'}</TableCell>
                      <TableCell>
                        <Chip label={dump.format} size="small" color={dump.format === 'SQL' ? 'primary' : dump.format === 'CUSTOM' ? 'secondary' : 'default'} variant="outlined" />
                      </TableCell>
                      <TableCell>{formatBytes(dump.fileSize)}</TableCell>
                      <TableCell>
                        <Typography variant="caption" fontFamily="monospace" title={dump.md5Hash} sx={{ cursor: 'default' }}>
                          {dump.md5Hash ? dump.md5Hash.substring(0, 8) + '...' : '-'}
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(dump.uploadedAt)}</TableCell>
                      <TableCell>
                        <Chip
                          icon={<Timer />}
                          label={dump.expiresAt ? formatDate(dump.expiresAt) : 'No expiration'}
                          size="small"
                          color={dump.expiresAt && new Date(dump.expiresAt) < new Date() ? 'error' : 'default'}
                          variant="outlined"
                          onClick={() => handleEditDumpExpiration(dump)}
                          sx={{ cursor: 'pointer' }}
                        />
                      </TableCell>
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 1 }}>
                          <Button size="small" variant="contained" color="primary" startIcon={<Download />} href={`/api/database/dumps/download/${dump.id}`}>
                            Download
                          </Button>
                          <Button size="small" variant="contained" color="success" startIcon={<Restore />} onClick={() => handleRestoreClick(dump)}>
                            Restore
                          </Button>
                          <Button size="small" variant="contained" color="error" startIcon={<Delete />} onClick={() => handleDeleteClick(dump)}>
                            Delete
                          </Button>
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
            <TextField
              fullWidth
              placeholder="Search snapshots..."
              value={snapFilter}
              onChange={(e) => setSnapFilter(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search color="action" /></InputAdornment> } }}
            />
            <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2 }}>
              <Table>
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
                        No snapshots found
                      </TableCell>
                    </TableRow>
                  )}
                  {filteredSnapshots.map((snap) => (
                    <Tooltip
                      key={snap.id}
                      title={snap.description ? (
                        <Box sx={{ p: 0.5 }}>
                          <Typography variant="caption" fontWeight={700} sx={{ display: 'block', mb: 0.5, opacity: 0.8 }}>
                            Description
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
                            '& .MuiTooltip-arrow': { color: 'primary.dark' },
                          },
                        },
                      }}
                    >
                    <TableRow hover selected={snapSelected.has(snap.id)}>
                      <TableCell padding="checkbox">
                        <Checkbox checked={snapSelected.has(snap.id)} onChange={() => toggleSnapSelect(snap.id)} />
                      </TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                          {snap.label || '-'}
                          {snap.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                        </Box>
                      </TableCell>
                      <TableCell>{snap.repository}</TableCell>
                      <TableCell>{snap.sourceDatabaseName}</TableCell>
                      <TableCell>{snap.containerName || '-'}</TableCell>
                      <TableCell>
                        <Chip label={snap.format} size="small" color={snap.format === 'SQL' ? 'primary' : 'secondary'} variant="outlined" />
                      </TableCell>
                      <TableCell>{formatBytes(snap.fileSize)}</TableCell>
                      <TableCell>
                        <Typography variant="caption" fontFamily="monospace" title={snap.md5Hash} sx={{ cursor: 'default' }}>
                          {snap.md5Hash ? snap.md5Hash.substring(0, 8) + '...' : '-'}
                        </Typography>
                      </TableCell>
                      <TableCell>{formatDate(snap.createdAt)}</TableCell>
                      <TableCell>
                        <Chip
                          icon={<Timer />}
                          label={snap.expiresAt ? formatDate(snap.expiresAt) : 'No expiration'}
                          size="small"
                          color={snap.expiresAt && new Date(snap.expiresAt) < new Date() ? 'error' : 'default'}
                          variant="outlined"
                          onClick={() => handleEditSnapExpiration(snap)}
                          sx={{ cursor: 'pointer' }}
                        />
                      </TableCell>
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 1 }}>
                          <Button size="small" variant="contained" color="primary" startIcon={<Download />} href={`/api/database/snapshots/download/${snap.id}`}>
                            Download
                          </Button>
                          <Button size="small" variant="contained" color="success" startIcon={<Restore />} onClick={() => { setRestoreSnapshot(snap); setRestoreSnapOpen(true) }}>
                            Restore
                          </Button>
                          <Button size="small" variant="contained" color="error" startIcon={<Delete />} onClick={() => handleSnapDeleteClick(snap)}>
                            Delete
                          </Button>
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

      {/* Dump delete dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> Delete {isBulkDelete ? `${selected.size} Dumps` : 'Dump'}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {isBulkDelete ? (
            <Typography sx={{ mb: 2 }}>
              Delete <strong>{selected.size}</strong> selected dump{selected.size === 1 ? '' : 's'}? This action cannot be undone.
            </Typography>
          ) : (
            <Typography sx={{ mb: 2 }}>
              Delete dump <strong>{deletingDump?.originalFilename}</strong>? This action cannot be undone.
            </Typography>
          )}
          <TextField
            fullWidth
            type="password"
            label="Operations Password"
            value={deletePassword}
            onChange={(e) => setDeletePassword(e.target.value)}
            size="small"
            autoComplete="off"
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setDeleteDialogOpen(false)} color="inherit" disabled={deleteLoading}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color="error"
            onClick={handleDeleteConfirm}
            disabled={deleteLoading || !deletePassword}
            startIcon={deleteLoading ? <CircularProgress size={20} /> : <Delete />}
          >
            {deleteLoading ? 'Deleting...' : 'Delete'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Snapshot delete dialog */}
      <Dialog
        open={snapDeleteDialogOpen}
        onClose={() => setSnapDeleteDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> Delete {isSnapBulkDelete ? `${snapSelected.size} Snapshots` : 'Snapshot'}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {isSnapBulkDelete ? (
            <Typography sx={{ mb: 2 }}>
              Delete <strong>{snapSelected.size}</strong> selected snapshot{snapSelected.size === 1 ? '' : 's'}? This action cannot be undone.
            </Typography>
          ) : (
            <Typography sx={{ mb: 2 }}>
              Delete snapshot <strong>{deletingSnapshot?.label || deletingSnapshot?.sourceDatabaseName}</strong>? This action cannot be undone.
            </Typography>
          )}
          <TextField
            fullWidth
            type="password"
            label="Operations Password"
            value={snapDeletePassword}
            onChange={(e) => setSnapDeletePassword(e.target.value)}
            size="small"
            autoComplete="off"
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setSnapDeleteDialogOpen(false)} color="inherit" disabled={snapDeleteLoading}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color="error"
            onClick={handleSnapDeleteConfirm}
            disabled={snapDeleteLoading || !snapDeletePassword}
            startIcon={snapDeleteLoading ? <CircularProgress size={20} /> : <Delete />}
          >
            {snapDeleteLoading ? 'Deleting...' : 'Delete'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
