import { useState, useEffect, useMemo } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  IconButton,
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
  Chip,
  Tabs,
  Tab,
  Box,
  CircularProgress,
} from '@mui/material'
import { Close, Search } from '@mui/icons-material'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { listSnapshots } from '../services/snapshotService'
import { formatBytes, formatDate } from '../utils/format'

interface Props {
  open: boolean
  dumps: DatabaseDump[]
  onClose: () => void
  onSelect: (dump: DatabaseDump) => void
  onSelectSnapshot?: (snapshot: DatabaseSnapshot) => void
}

const DUMP_COLUMNS: { key: string; label: string }[] = [
  { key: 'originalFilename', label: 'Filename' },
  { key: 'version', label: 'Version' },
  { key: 'databaseName', label: 'Database' },
  { key: 'format', label: 'Format' },
  { key: 'fileSize', label: 'Size' },
  { key: 'uploadedAt', label: 'Uploaded At' },
  { key: 'action', label: '' },
]

const SNAP_COLUMNS: { key: string; label: string }[] = [
  { key: 'label', label: 'Label' },
  { key: 'sourceDatabaseName', label: 'Database' },
  { key: 'repository', label: 'Repository' },
  { key: 'format', label: 'Format' },
  { key: 'fileSize', label: 'Size' },
  { key: 'createdAt', label: 'Created At' },
  { key: 'action', label: '' },
]

export default function DumpBrowserModal({ open, dumps, onClose, onSelect, onSelectSnapshot }: Props) {
  const [tab, setTab] = useState(0)
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [snapshots, setSnapshots] = useState<DatabaseSnapshot[]>([])
  const [snapLoading, setSnapLoading] = useState(false)

  useEffect(() => {
    if (open && tab === 1) {
      setSnapLoading(true)
      listSnapshots()
        .then(setSnapshots)
        .catch(() => setSnapshots([]))
        .finally(() => setSnapLoading(false))
    }
  }, [open, tab])

  function handleSort(key: string) {
    if (key === 'action') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  function handleTabChange(_e: React.SyntheticEvent, newTab: number) {
    setTab(newTab)
    setFilter('')
    setSortKey('')
  }

  const filteredDumps = useMemo(() => {
    const lc = filter.toLowerCase()
    const result = dumps.filter((d) =>
      [d.originalFilename, d.version ?? '', d.databaseName ?? '', d.format]
        .some((v) => v.toLowerCase().includes(lc)),
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

  const filteredSnapshots = useMemo(() => {
    const lc = filter.toLowerCase()
    const result = snapshots.filter((s) =>
      [s.label ?? '', s.sourceDatabaseName, s.repository, s.format]
        .some((v) => v.toLowerCase().includes(lc)),
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
  }, [snapshots, filter, sortKey, sortDir])

  function handleSelectDump(dump: DatabaseDump) {
    onSelect(dump)
    onClose()
    resetState()
  }

  function handleSelectSnapshot(snap: DatabaseSnapshot) {
    onSelectSnapshot?.(snap)
    onClose()
    resetState()
  }

  function handleClose() {
    onClose()
    resetState()
  }

  function resetState() {
    setFilter('')
    setSortKey('')
    setTab(0)
  }

  const columns = tab === 0 ? DUMP_COLUMNS : SNAP_COLUMNS

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        Browse Files
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 0 }}>
        <Tabs value={tab} onChange={handleTabChange} sx={{ mb: 2 }}>
          <Tab label="Uploaded Dumps" />
          <Tab label="Snapshots" disabled={!onSelectSnapshot} />
        </Tabs>

        <TextField
          fullWidth
          placeholder={tab === 0 ? 'Search dumps...' : 'Search snapshots...'}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          size="small"
          sx={{ mb: 2 }}
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

        <TableContainer component={Paper} elevation={0} sx={{ borderRadius: 2, maxHeight: 400 }}>
          <Table stickyHeader size="small">
            <TableHead>
              <TableRow>
                {columns.map((col) => (
                  <TableCell key={col.key} sx={{ fontWeight: 600 }}>
                    {col.key !== 'action' ? (
                      <TableSortLabel
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                        onClick={() => handleSort(col.key)}
                      >
                        {col.label}
                      </TableSortLabel>
                    ) : null}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {/* Dumps tab */}
              {tab === 0 && filteredDumps.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns.length} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    No dumps found
                  </TableCell>
                </TableRow>
              )}
              {tab === 0 && filteredDumps.map((dump) => (
                <TableRow key={dump.id} hover>
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
                  <TableCell>{formatDate(dump.uploadedAt)}</TableCell>
                  <TableCell>
                    <Button size="small" variant="contained" color="primary" onClick={() => handleSelectDump(dump)}>
                      Select
                    </Button>
                  </TableCell>
                </TableRow>
              ))}

              {/* Snapshots tab */}
              {tab === 1 && snapLoading && (
                <TableRow>
                  <TableCell colSpan={columns.length} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={24} />
                  </TableCell>
                </TableRow>
              )}
              {tab === 1 && !snapLoading && filteredSnapshots.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns.length} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    No snapshots found
                  </TableCell>
                </TableRow>
              )}
              {tab === 1 && !snapLoading && filteredSnapshots.map((snap) => (
                <TableRow key={snap.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{snap.label || '-'}</TableCell>
                  <TableCell>{snap.sourceDatabaseName}</TableCell>
                  <TableCell>{snap.repository}</TableCell>
                  <TableCell>
                    <Chip
                      label={snap.format}
                      size="small"
                      color={snap.format === 'SQL' ? 'primary' : 'secondary'}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>{formatBytes(snap.fileSize)}</TableCell>
                  <TableCell>{formatDate(snap.createdAt)}</TableCell>
                  <TableCell>
                    <Button size="small" variant="contained" color="primary" onClick={() => handleSelectSnapshot(snap)}>
                      Select
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </DialogContent>
    </Dialog>
  )
}
