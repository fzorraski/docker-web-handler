import { useState, useEffect, useMemo, type ReactNode } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  IconButton,
  TextField,
  InputAdornment,
  MenuItem,
  Chip,
  Tabs,
  Tab,
  Box,
  CircularProgress,
  Tooltip,
  Typography,
} from '@mui/material'
import { ArrowDownward, ArrowUpward, Close, Search, InfoOutlined } from '@mui/icons-material'
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

const smallChipSx = { height: 22, '& .MuiChip-label': { px: 0.75, fontSize: '0.75rem' } }

const descriptionTooltipSx = {
  bgcolor: 'grey.800',
  maxWidth: 300,
  borderRadius: 1.5,
  border: 1,
  borderColor: 'grey.600',
  px: 1.5, py: 1,
  boxShadow: 8,
  '& .MuiTooltip-arrow': { color: 'grey.800' },
}

function filterAndSort<T extends { fileSize: number }>(
  items: T[],
  filter: string,
  filterFields: (keyof T)[],
  sortKey: string,
  sortDir: 'asc' | 'desc',
): T[] {
  const lc = filter.toLowerCase()
  const filtered = items.filter((item) =>
    filterFields.some((f) => String(item[f] ?? '').toLowerCase().includes(lc)),
  )
  if (!sortKey) return filtered
  return [...filtered].sort((a, b) => {
    if (sortKey === 'fileSize') {
      const cmp = a.fileSize - b.fileSize
      return sortDir === 'asc' ? cmp : -cmp
    }
    const va = String((a as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
    const vb = String((b as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
    const cmp = va.localeCompare(vb)
    return sortDir === 'asc' ? cmp : -cmp
  })
}

export default function DumpBrowserModal({ open, dumps, onClose, onSelect, onSelectSnapshot }: Props) {
  const { t } = useTranslation()
  const [tab, setTab] = useState(0)
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState('uploadedAt')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
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

  function handleTabChange(_e: React.SyntheticEvent, newTab: number) {
    setTab(newTab)
    setFilter('')
    setSortKey(newTab === 0 ? 'uploadedAt' : 'createdAt')
    setSortDir('desc')
  }

  function handleSortChange(key: string) {
    if (key === sortKey) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir(key === 'fileSize' || key === 'uploadedAt' || key === 'createdAt' ? 'desc' : 'asc')
    }
  }

  const filteredDumps = useMemo(() =>
    filterAndSort(dumps, filter, ['originalFilename', 'version', 'databaseName', 'format', 'description'], sortKey, sortDir),
    [dumps, filter, sortKey, sortDir],
  )

  const filteredSnapshots = useMemo(() =>
    filterAndSort(snapshots, filter, ['label', 'sourceDatabaseName', 'repository', 'format', 'description'], sortKey, sortDir),
    [snapshots, filter, sortKey, sortDir],
  )

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
    setSortKey('uploadedAt')
    setSortDir('desc')
    setTab(0)
  }

  const dumpSortOptions = useMemo(() => [
    { key: 'originalFilename', label: t('dumpBrowser.dumpColumns.filename') },
    { key: 'version', label: t('dumpBrowser.dumpColumns.version') },
    { key: 'fileSize', label: t('dumpBrowser.dumpColumns.size') },
    { key: 'uploadedAt', label: t('dumpBrowser.dumpColumns.uploadedAt') },
  ], [t])

  const snapSortOptions = useMemo(() => [
    { key: 'label', label: t('dumpBrowser.snapColumns.label') },
    { key: 'sourceDatabaseName', label: t('dumpBrowser.snapColumns.database') },
    { key: 'fileSize', label: t('dumpBrowser.snapColumns.size') },
    { key: 'createdAt', label: t('dumpBrowser.snapColumns.createdAt') },
  ], [t])

  const sortOptions = tab === 0 ? dumpSortOptions : snapSortOptions

  function renderCard(
    id: string,
    title: string,
    size: number,
    description: string | undefined,
    metadata: ReactNode,
    onClick: () => void,
  ) {
    return (
      <Tooltip
        key={id}
        title={description ? (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>{description}</Typography>
        ) : ''}
        placement="top"
        arrow
        disableHoverListener={!description}
        slotProps={{ tooltip: { sx: descriptionTooltipSx } }}
      >
        <Box
          onClick={onClick}
          sx={{
            p: 1.5,
            border: 1,
            borderColor: 'divider',
            borderRadius: 1.5,
            cursor: 'pointer',
            transition: 'border-color 0.15s, background-color 0.15s',
            '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
          }}
        >
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', mb: 0.5 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 0 }}>
              <Typography variant="body2" fontWeight={600} noWrap sx={{ minWidth: 0 }}>
                {title}
              </Typography>
              {description && <InfoOutlined sx={{ fontSize: 15, color: 'text.disabled', flexShrink: 0 }} />}
            </Box>
            <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0, ml: 1 }}>
              {formatBytes(size)}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', gap: 0.75, alignItems: 'center', flexWrap: 'wrap' }}>
            {metadata}
          </Box>
        </Box>
      </Tooltip>
    )
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
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

        <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
          <TextField
            fullWidth
            placeholder={tab === 0 ? t('dumpBrowser.searchDumps') : t('dumpBrowser.searchSnapshots')}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            size="small"
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
          <TextField
            select
            value={sortKey}
            onChange={(e) => handleSortChange(e.target.value)}
            size="small"
            label={t('dumpBrowser.sortBy')}
            sx={{ minWidth: 150 }}
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end" sx={{ mr: 2 }}>
                    <IconButton size="small" onClick={() => setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))}>
                      {sortDir === 'asc' ? <ArrowUpward sx={{ fontSize: 16 }} /> : <ArrowDownward sx={{ fontSize: 16 }} />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          >
            {sortOptions.map((opt) => (
              <MenuItem key={opt.key} value={opt.key}>{opt.label}</MenuItem>
            ))}
          </TextField>
        </Box>

        <Box sx={{ maxHeight: 420, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 1 }}>
          {/* Dumps tab */}
          {tab === 0 && filteredDumps.length === 0 && (
            <Typography color="text.secondary" align="center" sx={{ py: 6 }}>
              {t('dumpBrowser.noDumpsFound')}
            </Typography>
          )}
          {tab === 0 && filteredDumps.map((dump) =>
            renderCard(dump.id, dump.originalFilename, dump.fileSize, dump.description, <>
              {dump.version && <Chip label={dump.version} size="small" variant="outlined" sx={smallChipSx} />}
              {dump.databaseName && <Typography variant="caption" color="text.secondary">{dump.databaseName}</Typography>}
              <Chip
                label={dump.format}
                size="small"
                color={dump.format === 'SQL' ? 'primary' : dump.format === 'CUSTOM' ? 'secondary' : 'default'}
                variant="outlined"
                sx={smallChipSx}
              />
              <Typography variant="caption" color="text.disabled">{formatDate(dump.uploadedAt)}</Typography>
            </>, () => handleSelectDump(dump)),
          )}

          {/* Snapshots tab */}
          {tab === 1 && snapLoading && (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
              <CircularProgress size={24} />
            </Box>
          )}
          {tab === 1 && !snapLoading && filteredSnapshots.length === 0 && (
            <Typography color="text.secondary" align="center" sx={{ py: 6 }}>
              {t('dumpBrowser.noSnapshotsFound')}
            </Typography>
          )}
          {tab === 1 && !snapLoading && filteredSnapshots.map((snap) =>
            renderCard(snap.id, snap.label || snap.sourceDatabaseName, snap.fileSize, snap.description, <>
              <Typography variant="caption" color="text.secondary">{snap.sourceDatabaseName}</Typography>
              <Typography variant="caption" color="text.disabled">{snap.repository}</Typography>
              <Chip
                label={snap.format}
                size="small"
                color={snap.format === 'SQL' ? 'primary' : 'secondary'}
                variant="outlined"
                sx={smallChipSx}
              />
              <Typography variant="caption" color="text.disabled">{formatDate(snap.createdAt)}</Typography>
            </>, () => handleSelectSnapshot(snap)),
          )}
        </Box>
      </DialogContent>
    </Dialog>
  )
}
