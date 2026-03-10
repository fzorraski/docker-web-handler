import { useState, useEffect, useCallback } from 'react'
import type { DockerContainer } from '../types'
import {
  getContainers,
  stopContainer,
  startContainer,
  removeContainer,
  getAllowedRepositories,
} from '../services/containerService'
import NewContainerModal from '../components/NewContainerModal'
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
} from '@mui/material'
import { Search, AddCircleOutline, Stop, PlayArrow, Delete } from '@mui/icons-material'

export default function ContainersPage() {
  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [hasRepos, setHasRepos] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)

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
    if (!confirm(`Stop ${id} ?`)) return
    await stopContainer(id)
    loadContainers()
  }

  async function handleStart(id: string) {
    if (!confirm(`Start ${id} ?`)) return
    await startContainer(id)
    loadContainers()
  }

  async function handleRemove(id: string) {
    if (!confirm(`Remove ${id} ?`)) return
    await removeContainer(id)
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
                {['Container ID', 'Image', 'Command', 'Created', 'Status', 'Ports', 'Name', 'Actions'].map((h) => (
                  <TableCell key={h} sx={{ color: 'white', fontWeight: 600 }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ py: 4, color: 'text.secondary' }}>
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
    </>
  )
}
