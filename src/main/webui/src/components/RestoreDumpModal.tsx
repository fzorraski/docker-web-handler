import { useState, useEffect, useRef } from 'react'
import {
  Autocomplete,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  MenuItem,
  Grid,
  IconButton,
  FormControlLabel,
  Switch,
  CircularProgress,
} from '@mui/material'
import { Close, Restore } from '@mui/icons-material'
import type { DatabaseDump } from '../types'
import { getDumpRepositories, cancelRestore } from '../services/dumpService'
import { getRepositoryDatabases } from '../services/containerService'
import { prepareRestoreDump, streamRestoreDump, type ContainerEvent } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import OperationProgress, { RESTORE_STEPS } from './OperationProgress'

interface Props {
  open: boolean
  dump: DatabaseDump | null
  onClose: () => void
  onRestored: () => void
}

export default function RestoreDumpModal({ open, dump, onClose, onRestored }: Props) {
  const { notify } = useNotification()
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [databases, setDatabases] = useState<string[]>([])
  const [dbLoading, setDbLoading] = useState(false)
  const [targetDb, setTargetDb] = useState<string>('')
  const [createDb, setCreateDb] = useState(false)
  const [password, setPassword] = useState('')
  const [running, setRunning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [sseEvents, setSseEvents] = useState<ContainerEvent[]>([])
  const [sseError, setSseError] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (open) {
      getDumpRepositories().then(setRepositories).catch(() => setRepositories([]))
    }
  }, [open])

  useEffect(() => {
    if (!selectedRepo) {
      setDatabases([])
      return
    }
    setDbLoading(true)
    getRepositoryDatabases(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.databases) setDatabases(res.databases)
        else setDatabases([])
      })
      .catch(() => setDatabases([]))
      .finally(() => setDbLoading(false))
  }, [selectedRepo])

  useEffect(() => {
    if (open && dump) {
      const parts: string[] = []
      if (dump.databaseName) parts.push(dump.databaseName)
      if (dump.version) parts.push(dump.version)
      if (dump.uploadedAt) {
        const d = new Date(dump.uploadedAt)
        if (!isNaN(d.getTime())) {
          parts.push(d.toISOString().slice(0, 10))
        }
      }
      setTargetDb(parts.length > 0 ? parts.join('_') : '')
    }
  }, [open, dump])

  function resetForm() {
    setSelectedRepo('')
    setDatabases([])
    setTargetDb('')
    setCreateDb(false)
    setPassword('')
    setSseEvents([])
    setSseError(false)
    setRunning(false)
    setCancelling(false)
  }

  function handleClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    onClose()
    // Reset after close so the dialog content doesn't flash empty during closing animation
    setTimeout(resetForm, 300)
  }

  async function handleCancel() {
    if (!selectedRepo || !targetDb.trim()) return
    setCancelling(true)
    await cancelRestore(selectedRepo, targetDb.trim())
    // The backend will send an ERROR event through SSE which triggers the onError handler
  }

  async function handleRestore() {
    if (!dump) return
    if (!selectedRepo) return notify('Please select a repository.', 'warning')
    if (!targetDb.trim()) return notify('Please enter a target database.', 'warning')
    if (!password) return notify('Please enter the operations password.', 'warning')

    setRunning(true)
    setSseEvents([])
    setSseError(false)

    try {
      const isNew = !databases.includes(targetDb.trim())

      const ticket = await prepareRestoreDump({
        dumpId: dump.id,
        repository: selectedRepo,
        targetDatabase: targetDb.trim(),
        createDatabase: createDb || isNew,
        password,
      })

      cleanupSse.current = streamRestoreDump(
        ticket,
        (event) => setSseEvents((prev) => [...prev, event]),
        () => {
          setTimeout(() => {
            onClose()
            onRestored()
            notify('Dump restored successfully.', 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
        () => {
          setRunning(false)
          setSseError(true)
        },
      )
    } catch (e) {
      setRunning(false)
      notify(e instanceof Error ? e.message : 'An unexpected error occurred.', 'error')
    }
  }

  return (
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (running && (reason === 'escapeKeyDown' || reason === 'backdropClick')) return
        handleClose()
      }}
      maxWidth="md"
      fullWidth
    >
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Restore sx={{ mr: 1 }} /> Restore Dump {dump ? `- ${dump.originalFilename}` : ''}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {running || sseEvents.length > 0 ? (
          <OperationProgress events={sseEvents} steps={RESTORE_STEPS} />
        ) : (
          <>
            <TextField
              fullWidth
              type="password"
              label="Operations Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              autoComplete="off"
            />

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  select
                  fullWidth
                  label="Repository"
                  value={selectedRepo}
                  onChange={(e) => setSelectedRepo(e.target.value)}
                  size="small"
                >
                  <MenuItem value="">Select a repository...</MenuItem>
                  {repositories.map((r) => (
                    <MenuItem key={r} value={r}>{r}</MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <Autocomplete
                  freeSolo
                  options={databases}
                  value={targetDb}
                  onInputChange={(_e, value) => {
                    setTargetDb(value)
                    if (value && !databases.includes(value)) {
                      setCreateDb(true)
                    }
                  }}
                  loading={dbLoading}
                  disabled={!selectedRepo}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Target Database"
                      placeholder="Select or type a new database name"
                      size="small"
                      slotProps={{
                        input: {
                          ...params.InputProps,
                          endAdornment: (
                            <>
                              {dbLoading ? <CircularProgress size={20} /> : null}
                              {params.InputProps.endAdornment}
                            </>
                          ),
                        },
                      }}
                    />
                  )}
                />
              </Grid>
            </Grid>

            <FormControlLabel
              control={
                <Switch
                  checked={createDb}
                  onChange={(e) => setCreateDb(e.target.checked)}
                />
              }
              label="Create database if it doesn't exist"
            />
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {sseError ? (
          <>
            <Button onClick={handleClose} color="inherit">Close</Button>
            <Button
              variant="contained"
              color="primary"
              onClick={() => {
                setSseEvents([])
                setSseError(false)
              }}
            >
              Back to Form
            </Button>
          </>
        ) : running ? (
          <Button
            onClick={handleCancel}
            color="error"
            variant="contained"
            disabled={cancelling}
            startIcon={cancelling ? <CircularProgress size={20} /> : undefined}
          >
            {cancelling ? 'Cancelling...' : 'Cancel Restore'}
          </Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">Cancel</Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleRestore}
              disabled={running || !selectedRepo || !targetDb.trim() || !password}
              startIcon={<Restore />}
            >
              Restore
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
