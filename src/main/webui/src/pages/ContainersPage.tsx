import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { DockerContainer } from '../types'
import {
  getContainers,
  stopContainer,
  startContainer,
  getAllowedRepositories,
} from '../services/containerService'
import { streamRemoveContainer, type ContainerEvent } from '../services/sseService'
import NewContainerModal from '../components/NewContainerModal'
import OperationProgress, { REMOVE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
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
  Paper,
  Chip,
  CircularProgress,
  Link as MuiLink,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
} from '@mui/material'
import { Search, AddCircleOutline, Stop, PlayArrow, Delete, Timer } from '@mui/icons-material'

export default function ContainersPage() {
  const { notify, confirm } = useNotification()
  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasRepos, setHasRepos] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [removeDialogOpen, setRemoveDialogOpen] = useState(false)
  const [removeEvents, setRemoveEvents] = useState<ContainerEvent[]>([])
  const [removeError, setRemoveError] = useState(false)
  const [removeDone, setRemoveDone] = useState(false)
  const cleanupRemoveSse = useRef<(() => void) | null>(null)

  const machineIp = window.location.hostname

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
  }, [loadContainers])

  async function handleStop(id: string) {
    if (!(await confirm(`Stop container ${id}?`))) return
    try {
      const ok = await stopContainer(id)
      notify(ok ? 'Container stopped.' : 'Failed to stop container.', ok ? 'success' : 'error')
    } catch {
      notify('An unexpected error occurred while stopping the container.', 'error')
    }
    loadContainers()
  }

  async function handleStart(id: string) {
    if (!(await confirm(`Start container ${id}?`))) return
    try {
      const ok = await startContainer(id)
      notify(ok ? 'Container started.' : 'Failed to start container.', ok ? 'success' : 'error')
    } catch {
      notify('An unexpected error occurred while starting the container.', 'error')
    }
    loadContainers()
  }

  async function handleRemove(id: string) {
    if (!(await confirm(`Remove container ${id}? This action cannot be undone.`))) return

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
          notify('Container removed.', 'success')
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

  const isUp = (status: string) => status.includes('Up')

  const filtered = containers.filter((c) =>
    Object.values(c).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
  )

  return (
    <>
      <HeroBanner linkTo="/images" linkLabel="Explore Images" />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" fontWeight="bold">Containers</Typography>
          {hasRepos && (
            <Button
              variant="contained"
              color="success"
              startIcon={<AddCircleOutline />}
              onClick={() => setModalOpen(true)}
            >
              New Container
            </Button>
          )}
        </Box>

        <TextField
          fullWidth
          placeholder="Search containers..."
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
                {['Container ID', 'Image', 'Command', 'Created', 'Status', 'Ports', 'Name', 'Expires', 'Actions'].map((h) => (
                  <TableCell key={h} sx={{ color: 'white', fontWeight: 600 }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    No containers found
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((c) => (
                <TableRow key={c.containerId} hover>
                  <TableCell>{c.containerId}</TableCell>
                  <TableCell>{c.image}</TableCell>
                  <TableCell>{c.command}</TableCell>
                  <TableCell>{c.created}</TableCell>
                  <TableCell>
                    <Chip
                      label={c.status}
                      size="small"
                      color={isUp(c.status) ? 'success' : 'default'}
                      variant={isUp(c.status) ? 'filled' : 'outlined'}
                    />
                  </TableCell>
                  <TableCell>
                    {c.ports !== '-'
                      ? c.ports.split(',').map((port, i) => (
                          <MuiLink
                            key={i}
                            href={`http://${machineIp}:${port.trim()}`}
                            target="_blank"
                            rel="noreferrer"
                            sx={{ mr: 1, fontWeight: 600 }}
                          >
                            {port.trim()}
                          </MuiLink>
                        ))
                      : '-'}
                  </TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{c.names}</TableCell>
                  <TableCell>
                    {c.expiresAt ? (
                      <ExpirationChip expiresAt={c.expiresAt} />
                    ) : (
                      <Typography variant="body2" color="text.secondary">-</Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', gap: 0.5 }}>
                      {isUp(c.status) ? (
                        <Button
                          size="small"
                          variant="contained"
                          color="warning"
                          startIcon={<Stop />}
                          onClick={() => handleStop(c.containerId)}
                        >
                          Stop
                        </Button>
                      ) : (
                        <Button
                          size="small"
                          variant="contained"
                          color="primary"
                          startIcon={<PlayArrow />}
                          onClick={() => handleStart(c.containerId)}
                        >
                          Start
                        </Button>
                      )}
                      <Button
                        size="small"
                        variant="contained"
                        color="error"
                        startIcon={<Delete />}
                        onClick={() => handleRemove(c.containerId)}
                      >
                        Remove
                      </Button>
                    </Box>
                  </TableCell>
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
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> Removing Container
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeEvents} steps={REMOVE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeError || removeDone) && (
            <Button onClick={handleRemoveDialogClose} color="inherit">Close</Button>
          )}
        </DialogActions>
      </Dialog>
    </>
  )
}

function ExpirationChip({ expiresAt }: { expiresAt: string }) {
  const expiresMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState('')

  useEffect(() => {
    function update() {
      const diff = expiresMs - Date.now()
      if (diff <= 0) {
        setRemaining('Expiring...')
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
  }, [expiresMs])

  return (
    <Chip
      label={remaining}
      size="small"
      color="warning"
      icon={<Timer />}
      variant="outlined"
    />
  )
}
