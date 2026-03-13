import { useState, useMemo } from 'react'
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
} from '@mui/material'
import { Close, Search } from '@mui/icons-material'
import type { DatabaseDump } from '../types'
import { formatBytes, formatDate } from '../utils/format'

interface Props {
  open: boolean
  dumps: DatabaseDump[]
  onClose: () => void
  onSelect: (dump: DatabaseDump) => void
}

const COLUMNS: { key: string; label: string }[] = [
  { key: 'originalFilename', label: 'Filename' },
  { key: 'version', label: 'Version' },
  { key: 'databaseName', label: 'Database' },
  { key: 'format', label: 'Format' },
  { key: 'fileSize', label: 'Size' },
  { key: 'uploadedAt', label: 'Uploaded At' },
  { key: 'action', label: '' },
]

export default function DumpBrowserModal({ open, dumps, onClose, onSelect }: Props) {
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')

  function handleSort(key: string) {
    if (key === 'action') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  const filtered = useMemo(() => {
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

  function handleSelect(dump: DatabaseDump) {
    onSelect(dump)
    onClose()
    setFilter('')
    setSortKey('')
  }

  function handleClose() {
    onClose()
    setFilter('')
    setSortKey('')
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        Browse Dumps
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <TextField
          fullWidth
          placeholder="Search dumps..."
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
                {COLUMNS.map((col) => (
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
              {filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={COLUMNS.length} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    No dumps found
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((dump) => (
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
                    <Button
                      size="small"
                      variant="contained"
                      color="primary"
                      onClick={() => handleSelect(dump)}
                    >
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
