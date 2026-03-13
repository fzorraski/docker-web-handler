import { useState, useEffect, useCallback, useMemo } from 'react'
import type { DatabaseDump } from '../types'
import { listDumps, deleteDump, deleteDumpsBulk, getStorageInfo, getActiveRestores, type ActiveRestore } from '../services/dumpService'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import UploadDumpModal from '../components/UploadDumpModal'
import RestoreDumpModal from '../components/RestoreDumpModal'
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
} from '@mui/material'
import { Search, Delete, CloudUpload, Download, Restore, Timer, Storage, InsertDriveFile } from '@mui/icons-material'

export default function DatabasePage() {
  const { notify } = useNotification()
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

  const COLUMNS: { key: string; label: string }[] = [
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

  useEffect(() => {
    loadDumps()
  }, [loadDumps])

  useEffect(() => {
    const check = () => getActiveRestores().then(setActiveRestores).catch(() => setActiveRestores([]))
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
    if (selected.size === filtered.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(filtered.map((d) => d.id)))
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

  const filtered = useMemo(() => {
    const result = dumps.filter((d) =>
      [d.originalFilename, d.databaseName ?? '', d.version ?? '', d.format, formatBytes(d.fileSize)]
        .some((v) => v.toLowerCase().includes(filter.toLowerCase())),
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      let va: string | number
      let vb: string | number
      if (sortKey === 'fileSize') {
        va = a.fileSize
        vb = b.fileSize
        const cmp = (va as number) - (vb as number)
        return sortDir === 'asc' ? cmp : -cmp
      }
      va = String((a as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
      vb = String((b as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
      const cmp = va.localeCompare(vb as string)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [dumps, filter, sortKey, sortDir])

  return (
    <>
      <HeroBanner linkTo="/" linkLabel="Explore Containers" />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" fontWeight="bold">
            Database Dumps
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            {selected.size > 0 && (
              <Button
                variant="contained"
                color="error"
                startIcon={<Delete />}
                onClick={handleBulkDeleteClick}
              >
                Delete ({selected.size})
              </Button>
            )}
            <Button
              variant="contained"
              color="primary"
              startIcon={<CloudUpload />}
              onClick={() => setUploadOpen(true)}
            >
              Upload Dump
            </Button>
          </Box>
        </Box>

        {storageInfo && storageInfo.maxBytes > 0 && (
          <Paper elevation={2} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
            <Grid container spacing={3} alignItems="center">
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Storage color="primary" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" fontWeight="bold" lineHeight={1.2}>
                      {formatBytes(storageInfo.totalBytes)}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      of {formatBytes(storageInfo.maxBytes)} used
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <InsertDriveFile color="action" sx={{ fontSize: 36 }} />
                  <Box>
                    <Typography variant="h6" fontWeight="bold" lineHeight={1.2}>
                      {storageInfo.fileCount}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {storageInfo.fileCount === 1 ? 'dump file' : 'dump files'}
                    </Typography>
                  </Box>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" color="text.secondary">Storage usage</Typography>
                    <Typography variant="body2" fontWeight="bold">
                      {storageInfo.maxBytes > 0 ? Math.min(100, (storageInfo.totalBytes / storageInfo.maxBytes * 100)).toFixed(1) : 0}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={Math.min(100, storageInfo.totalBytes / storageInfo.maxBytes * 100)}
                    sx={{
                      height: 10,
                      borderRadius: 5,
                      bgcolor: 'grey.200',
                      '& .MuiLinearProgress-bar': {
                        borderRadius: 5,
                        bgcolor: storageInfo.totalBytes / storageInfo.maxBytes > 0.9 ? 'error.main'
                          : storageInfo.totalBytes / storageInfo.maxBytes > 0.7 ? 'warning.main'
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

        <TextField
          fullWidth
          placeholder="Search dumps..."
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          size="small"
          sx={{ mb: 3 }}
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

        <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2 }}>
          <Table>
            <TableHead>
              <TableRow sx={{ bgcolor: 'primary.main' }}>
                <TableCell padding="checkbox" sx={{ bgcolor: 'primary.main' }}>
                  <Checkbox
                    checked={filtered.length > 0 && selected.size === filtered.length}
                    indeterminate={selected.size > 0 && selected.size < filtered.length}
                    onChange={toggleSelectAll}
                    sx={{ color: 'white', '&.Mui-checked': { color: 'white' }, '&.MuiCheckbox-indeterminate': { color: 'white' } }}
                  />
                </TableCell>
                {COLUMNS.map((col) => (
                  <TableCell key={col.key} sx={{ color: 'white', fontWeight: 600 }}>
                    {col.key !== 'action' ? (
                      <TableSortLabel
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                        onClick={() => handleSort(col.key)}
                        sx={{ color: 'white !important', '& .MuiTableSortLabel-icon': { color: 'white !important' } }}
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
                  <TableCell colSpan={COLUMNS.length + 1} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={COLUMNS.length + 1} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    No dumps found
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((dump) => (
                <TableRow key={dump.id} hover selected={selected.has(dump.id)}>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={selected.has(dump.id)}
                      onChange={() => toggleSelect(dump.id)}
                    />
                  </TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{dump.originalFilename}</TableCell>
                  <TableCell>{dump.version || '-'}</TableCell>
                  <TableCell>{dump.databaseName || '-'}</TableCell>
                  <TableCell>
                    <Chip
                      label={dump.format}
                      size="small"
                      color={dump.format === 'SQL' ? 'primary' : dump.format === 'CUSTOM' ? 'secondary' : 'default'}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>{formatBytes(dump.fileSize)}</TableCell>
                  <TableCell>
                    <Typography
                      variant="caption"
                      fontFamily="monospace"
                      title={dump.md5Hash}
                      sx={{ cursor: 'default' }}
                    >
                      {dump.md5Hash ? dump.md5Hash.substring(0, 8) + '...' : '-'}
                    </Typography>
                  </TableCell>
                  <TableCell>{formatDate(dump.uploadedAt)}</TableCell>
                  <TableCell>
                    {dump.expiresAt ? (
                      <Chip
                        icon={<Timer />}
                        label={formatDate(dump.expiresAt)}
                        size="small"
                        color={new Date(dump.expiresAt) < new Date() ? 'error' : 'default'}
                        variant="outlined"
                      />
                    ) : '-'}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', gap: 1 }}>
                      <Button
                        size="small"
                        variant="contained"
                        color="primary"
                        startIcon={<Download />}
                        href={`/api/database/dumps/download/${dump.id}`}
                      >
                        Download
                      </Button>
                      <Button
                        size="small"
                        variant="contained"
                        color="success"
                        startIcon={<Restore />}
                        onClick={() => handleRestoreClick(dump)}
                      >
                        Restore
                      </Button>
                      <Button
                        size="small"
                        variant="contained"
                        color="error"
                        startIcon={<Delete />}
                        onClick={() => handleDeleteClick(dump)}
                      >
                        Delete
                      </Button>
                    </Box>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

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
    </>
  )
}
