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
  Tooltip,
  Typography,
} from '@mui/material'
import { Close, Search, InfoOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
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

export default function DumpBrowserModal({ open, dumps, onClose, onSelect, onSelectSnapshot }: Props) {
  const { t } = useTranslation()
  const [tab, setTab] = useState(0)
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [snapshots, setSnapshots] = useState<DatabaseSnapshot[]>([])
  const [snapLoading, setSnapLoading] = useState(false)

  const DUMP_COLUMNS: { key: string; label: string }[] = useMemo(() => [
    { key: 'originalFilename', label: t('dumpBrowser.dumpColumns.filename') },
    { key: 'version', label: t('dumpBrowser.dumpColumns.version') },
    { key: 'databaseName', label: t('dumpBrowser.dumpColumns.database') },
    { key: 'format', label: t('dumpBrowser.dumpColumns.format') },
    { key: 'fileSize', label: t('dumpBrowser.dumpColumns.size') },
    { key: 'uploadedAt', label: t('dumpBrowser.dumpColumns.uploadedAt') },
    { key: 'action', label: '' },
  ], [t])

  const SNAP_COLUMNS: { key: string; label: string }[] = useMemo(() => [
    { key: 'label', label: t('dumpBrowser.snapColumns.label') },
    { key: 'sourceDatabaseName', label: t('dumpBrowser.snapColumns.database') },
    { key: 'repository', label: t('dumpBrowser.snapColumns.repository') },
    { key: 'format', label: t('dumpBrowser.snapColumns.format') },
    { key: 'fileSize', label: t('dumpBrowser.snapColumns.size') },
    { key: 'createdAt', label: t('dumpBrowser.snapColumns.createdAt') },
    { key: 'action', label: '' },
  ], [t])

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
      [d.originalFilename, d.version ?? '', d.databaseName ?? '', d.format, d.description ?? '']
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
      [s.label ?? '', s.sourceDatabaseName, s.repository, s.format, s.description ?? '']
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
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        {t('dumpBrowser.title')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 0 }}>
        <Tabs value={tab} onChange={handleTabChange} sx={{ mb: 2 }}>
          <Tab label={t('dumpBrowser.uploadedDumps')} />
          <Tab label={t('dumpBrowser.snapshots')} disabled={!onSelectSnapshot} />
        </Tabs>

        <TextField
          fullWidth
          placeholder={tab === 0 ? t('dumpBrowser.searchDumps') : t('dumpBrowser.searchSnapshots')}
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
                    {t('dumpBrowser.noDumpsFound')}
                  </TableCell>
                </TableRow>
              )}
              {tab === 0 && filteredDumps.map((dump) => (
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
                        '& .MuiTooltip-arrow': { color: 'primary.dark' },
                      },
                    },
                  }}
                >
                <TableRow hover>
                  <TableCell sx={{ fontWeight: 600 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      {dump.originalFilename}
                      {dump.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                    </Box>
                  </TableCell>
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
                      {t('common.select')}
                    </Button>
                  </TableCell>
                </TableRow>
                </Tooltip>
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
                    {t('dumpBrowser.noSnapshotsFound')}
                  </TableCell>
                </TableRow>
              )}
              {tab === 1 && !snapLoading && filteredSnapshots.map((snap) => (
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
                        '& .MuiTooltip-arrow': { color: 'primary.dark' },
                      },
                    },
                  }}
                >
                <TableRow hover>
                  <TableCell sx={{ fontWeight: 600 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      {snap.label || '-'}
                      {snap.description && <InfoOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />}
                    </Box>
                  </TableCell>
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
                      {t('common.select')}
                    </Button>
                  </TableCell>
                </TableRow>
                </Tooltip>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </DialogContent>
    </Dialog>
  )
}
