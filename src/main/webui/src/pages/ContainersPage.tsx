import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { DockerContainer } from '../types'
import {
  getContainers,
  stopContainer,
  startContainer,
  getAllowedRepositories,
  cancelDatabaseDeletion,
  cancelExpiration,
  extendExpiration,
  isDatabaseListingEnabled,
} from '../services/containerService'
import { isDumpEnabled, getActiveRestores, type ActiveRestore } from '../services/dumpService'
import { streamRemoveContainer, type ContainerEvent } from '../services/sseService'
import NewContainerModal from '../components/NewContainerModal'
import CreateSnapshotModal from '../components/CreateSnapshotModal'
import OperationProgress, { REMOVE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
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
  Link as MuiLink,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  IconButton,
  Tooltip,
  Menu,
  FormControlLabel,
  Checkbox,
  Alert,
  AlertTitle,
  useTheme,
} from '@mui/material'
import { Search, AddCircleOutline, Stop, PlayArrow, Delete, Timer, ViewColumn, Warning, MoreTime, CameraAlt } from '@mui/icons-material'

interface ColumnDef {
  key: string
  label: string
  defaultVisible: boolean
}

const STORAGE_KEY = 'containerColumnsVisibility'

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
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const theadBg = isDark ? 'background.paper' : 'primary.main'
  const theadColor = isDark ? 'text.primary' : 'white'
  const theadSortSx = isDark
    ? { color: 'text.primary !important', '& .MuiTableSortLabel-icon': { color: 'text.secondary !important' } }
    : { color: 'white !important', '& .MuiTableSortLabel-icon': { color: 'white !important' } }
  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasRepos, setHasRepos] = useState(false)
  const [dbListingEnabled, setDbListingEnabled] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [removeDialogOpen, setRemoveDialogOpen] = useState(false)
  const [removeEvents, setRemoveEvents] = useState<ContainerEvent[]>([])
  const [removeError, setRemoveError] = useState(false)
  const [removeDone, setRemoveDone] = useState(false)
  const cleanupRemoveSse = useRef<(() => void) | null>(null)
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

  const machineIp = window.location.hostname

  const BASE_COLUMNS: ColumnDef[] = useMemo(() => [
    { key: 'containerId', label: t('containers.columns.containerId'), defaultVisible: false },
    { key: 'image', label: t('containers.columns.image'), defaultVisible: true },
    { key: 'tag', label: t('containers.columns.tag'), defaultVisible: true },
    { key: 'command', label: t('containers.columns.command'), defaultVisible: false },
    { key: 'created', label: t('containers.columns.created'), defaultVisible: true },
    { key: 'status', label: t('containers.columns.status'), defaultVisible: true },
    { key: 'ports', label: t('containers.columns.ports'), defaultVisible: true },
    { key: 'names', label: t('containers.columns.name'), defaultVisible: true },
    { key: 'database', label: t('containers.columns.database'), defaultVisible: true },
    { key: 'expires', label: t('containers.columns.expires'), defaultVisible: true },
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

  useEffect(() => {
    loadContainers()
    getAllowedRepositories().then((repos) => setHasRepos(repos.length > 0)).catch(() => {})
    isDatabaseListingEnabled().then(setDbListingEnabled).catch(() => {})
    isDumpEnabled().then(setDumpEnabled).catch(() => {})
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

  async function handleStop(id: string) {
    if (!(await confirm(t('containers.confirmStop', { id })))) return
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

  async function handleStart(id: string) {
    if (!(await confirm(t('containers.confirmStart', { id })))) return
    try {
      const ok = await startContainer(id)
      notify(ok ? t('containers.containerStarted') : t('containers.failedToStart'), ok ? 'success' : 'error')
    } catch {
      notify(t('containers.startError'), 'error')
    }
    loadContainers()
  }

  async function handleRemove(id: string) {
    if (!(await confirm(t('containers.confirmRemove', { id })))) return

    setRemoveDialogOpen(true)
    setRemoveEvents([])
    setRemoveError(false)
    setRemoveDone(false)

    cleanupRemoveSse.current = streamRemoveContainer(
      id,
      (event) => setRemoveEvents((prev) => [...prev, event]),
      () => {
        setRemoveDone(true)
        setTimeout(() => {
          setRemoveDialogOpen(false)
          setRemoveEvents([])
          setRemoveDone(false)
          notify(t('containers.containerRemoved'), 'success')
          loadContainers()
        }, 1500)
      },
      () => {
        setRemoveError(true)
        loadContainers()
      },
    )
  }

  function handleRemoveDialogClose() {
    if (cleanupRemoveSse.current) {
      cleanupRemoveSse.current()
      cleanupRemoveSse.current = null
    }
    setRemoveDialogOpen(false)
    setRemoveEvents([])
    setRemoveError(false)
    setRemoveDone(false)
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

  async function handleCancelExpiration(id: string) {
    if (!(await confirm(t('containers.cancelExpiration', { id })))) return
    try {
      const ok = await cancelExpiration(id)
      notify(ok ? t('containers.expirationCancelled') : t('containers.failedToCancelExpiration'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }

  async function handleCancelDbDeletion(id: string) {
    if (!(await confirm(t('containers.cancelDbDeletion', { id })))) return
    try {
      const ok = await cancelDatabaseDeletion(id)
      notify(ok ? t('containers.dbDeletionCancelled') : t('containers.failedToCancelDbDeletion'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }

  function handleSnapshot(c: DockerContainer) {
    setSnapshotRepo(c.repository ?? undefined)
    setSnapshotDb(c.databaseName ?? undefined)
    setSnapshotContainerName(c.names)
    setSnapshotOpen(true)
  }

  function handleSort(key: string) {
    if (key === 'actions') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  const isUp = (status: string) => status.includes('Up')

  function getContainerValue(c: DockerContainer, key: string): string {
    switch (key) {
      case 'containerId': return c.containerId
      case 'image': return c.image
      case 'tag': return c.image.split(':')[1] ?? ''
      case 'command': return c.command
      case 'created': return c.created
      case 'status': return c.status
      case 'ports': return c.ports
      case 'names': return c.names
      case 'database': return c.databaseName ?? ''
      case 'expires': return c.expiresAt ?? ''
      default: return ''
    }
  }

  const filtered = useMemo(() => {
    const result = containers.filter((c) =>
      Object.values(c).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      const va = getContainerValue(a, sortKey).toLowerCase()
      const vb = getContainerValue(b, sortKey).toLowerCase()
      const cmp = va.localeCompare(vb)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [containers, filter, sortKey, sortDir])

  const dbsScheduledForDeletion = useMemo(() => {
    const map = new Map<string, string>()
    for (const c of containers) {
      if (c.databaseName && c.deleteDatabaseOnExpiration) {
        map.set(c.databaseName, c.names)
      }
    }
    return map
  }, [containers])

  return (
    <>
      <HeroBanner linkTo="/images" linkLabel={t('hero.exploreImages')} />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
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

        <Box sx={{ display: 'flex', gap: 1, mb: 3, alignItems: 'center' }}>
          <TextField
            fullWidth
            placeholder={t('containers.searchPlaceholder')}
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
          <Tooltip title={t('containers.toggleColumns')}>
            <IconButton onClick={(e) => setColumnMenuAnchor(e.currentTarget)}>
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

        <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2 }}>
          <Table>
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
                <TableRow key={c.containerId} hover>
                  {columnVisibility.containerId && <TableCell>{c.containerId}</TableCell>}
                  {columnVisibility.image && <TableCell>{c.image}</TableCell>}
                  {columnVisibility.tag && <TableCell>{c.image.split(':')[1] ?? '-'}</TableCell>}
                  {columnVisibility.command && <TableCell>{c.command}</TableCell>}
                  {columnVisibility.created && <TableCell>{formatBackendDate(c.created)}</TableCell>}
                  {columnVisibility.status && (
                    <TableCell>
                      <Chip
                        label={c.status}
                        size="small"
                        color={isUp(c.status) ? 'success' : 'default'}
                        variant={isUp(c.status) ? 'filled' : 'outlined'}
                      />
                    </TableCell>
                  )}
                  {columnVisibility.ports && (
                    <TableCell>
                      {c.ports !== '-'
                        ? c.ports.split(',').map((port, i) => (
                            <MuiLink
                              key={i}
                              href={`http://${machineIp}:${port.trim()}${c.portPaths?.[port.trim()] ?? ''}`}
                              target="_blank"
                              rel="noreferrer"
                              sx={{ mr: 1, fontWeight: 600 }}
                            >
                              {port.trim()}
                            </MuiLink>
                          ))
                        : '-'}
                    </TableCell>
                  )}
                  {columnVisibility.names && <TableCell sx={{ fontWeight: 600 }}>{c.names}</TableCell>}
                  {columnVisibility.database && (
                    <TableCell>
                      {c.databaseName ? (
                        <Typography variant="body2">{c.databaseName}</Typography>
                      ) : (
                        <Typography variant="body2" color="text.secondary">-</Typography>
                      )}
                    </TableCell>
                  )}
                  {columnVisibility.expires && (
                    <TableCell>
                      {c.expiresAt ? (
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <ExpirationChip expiresAt={c.expiresAt} onCancel={() => handleCancelExpiration(c.containerId)} onExpired={loadContainers} />
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
                                onDelete={() => handleCancelDbDeletion(c.containerId)}
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
                  {columnVisibility.actions && (
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.5 }}>
                        {isUp(c.status) ? (
                          <Button
                            size="small"
                            variant="contained"
                            color="warning"
                            startIcon={stoppingId === c.containerId ? <CircularProgress size={18} color="inherit" /> : <Stop />}
                            onClick={() => handleStop(c.containerId)}
                            disabled={stoppingId === c.containerId}
                          >
                            {stoppingId === c.containerId ? t('containers.stopping') : t('containers.stop')}
                          </Button>
                        ) : (
                          <Button
                            size="small"
                            variant="contained"
                            color="primary"
                            startIcon={<PlayArrow />}
                            onClick={() => handleStart(c.containerId)}
                          >
                            {t('containers.start')}
                          </Button>
                        )}
                        <Button
                          size="small"
                          variant="contained"
                          color="error"
                          startIcon={<Delete />}
                          onClick={() => handleRemove(c.containerId)}
                        >
                          {t('common.remove')}
                        </Button>
                        {dumpEnabled && c.repository && c.databaseName && (
                          <Tooltip title={t('containers.snapshotDatabase', { database: c.databaseName })}>
                            <Button
                              size="small"
                              variant="contained"
                              color="info"
                              startIcon={<CameraAlt />}
                              onClick={() => handleSnapshot(c)}
                            >
                              {t('containers.snapshot')}
                            </Button>
                          </Tooltip>
                        )}
                      </Box>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <NewContainerModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={loadContainers}
      />

      <Dialog open={removeDialogOpen} onClose={handleRemoveDialogClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('containers.removingContainer')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeEvents} steps={REMOVE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeError || removeDone) && (
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
    </>
  )
}

function ExpirationChip({ expiresAt, onCancel, onExpired }: { expiresAt: string; onCancel: () => void; onExpired: () => void }) {
  const { t } = useTranslation()
  const expiresMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState('')
  const expiredFired = useRef(false)

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
          setTimeout(onExpired, 6000)
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
    return () => clearInterval(id)
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
